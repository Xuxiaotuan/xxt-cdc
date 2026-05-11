package cn.xuyinyin.cdc.integration

import cn.xuyinyin.cdc.catalog.MySQLCatalogService
import cn.xuyinyin.cdc.config._
import cn.xuyinyin.cdc.connector.ConnectorConfig
import cn.xuyinyin.cdc.connector.jdbc.JdbcConnectionManager
import cn.xuyinyin.cdc.connector.sink.mysql.MySQLDataWriter
import cn.xuyinyin.cdc.coordinator.{DefaultOffsetCoordinator, FileOffsetStore}
import cn.xuyinyin.cdc.model.FilePosition
import cn.xuyinyin.cdc.normalizer.DebeziumEventNormalizer
import cn.xuyinyin.cdc.pipeline.CDCStreamPipeline
import cn.xuyinyin.cdc.reader.DebeziumBinlogReader
import cn.xuyinyin.cdc.router.HashBasedRouter
import cn.xuyinyin.cdc.worker.DefaultApplyWorker
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path, Paths}
import java.sql.DriverManager
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Using

/**
 * 真 Debezium engine + Testcontainers MySQL 端到端 Pipeline 测试。
 *
 * 验证 P0 关键设计契约：
 *   - **M2.1**: 真 Debezium engine 能从 testcontainers MySQL 读到 binlog
 *   - **M2.2 CORE**: 全流程跑完后 [[cn.xuyinyin.cdc.reader.AckRegistry.pendingCount]]
 *     回到 0，证明 commit 成功后 ack 闭环真生效（非空 swallow）
 *   - **M2.3**: source/target 表内容 MD5 一致（数据完整性）
 *
 * 单测试用例覆盖完整链路：DebeziumBinlogReader → Normalizer → Router →
 * ApplyWorker → DataWriter → OffsetCoordinator.commit → DebeziumBinlogReader.ack。
 *
 * 容器策略：单 MySQL 容器跑两个 schema（source_db 写入 + target_db 接收），
 * 减少容器启动时间。Debezium snapshot.mode=never，只验证增量 CDC。
 */
class RealDebeziumPipelineSpec
  extends AnyWordSpec
  with Matchers
  with BeforeAndAfterAll
  with Eventually {

  // 测试用 MySQL 容器：开启 binlog ROW + GTID（Debezium 必需）
  private val mysql: MySQLContainer[_] = {
    val c = new MySQLContainer(DockerImageName.parse("mysql:8.0"))
    c.withDatabaseName("source_db")
    c.withUsername("test")
    c.withPassword("test")
    c.withCommand(
      "--server-id=223344",
      "--log_bin=mysql-bin",
      "--binlog_format=ROW",
      "--binlog_row_image=FULL",
      "--gtid_mode=ON",
      "--enforce_gtid_consistency=ON"
    )
    c
  }

  implicit val system: ActorSystem    = ActorSystem("RealDebeziumPipelineSpec")
  implicit val mat: Materializer      = Materializer(system)
  implicit val ec: ExecutionContext   = system.dispatcher

  // 加宽轮询窗口：Debezium engine 启动 + commitInterval + 多次 batch 累积
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  // 每次运行独立的 task 名 + 数据目录，避免 offset/schema-history 文件互相污染
  private val taskName    = s"real-debezium-spec-${System.currentTimeMillis()}"
  private val tempDataDir = Files.createTempDirectory("cdc-debz-spec-")

  private var sourceDbConfig: DatabaseConfig = _
  private var targetDbConfig: DatabaseConfig = _

  // 测试期间持有，afterAll 释放
  private var pipeline: CDCStreamPipeline = _
  private var reader: DebeziumBinlogReader = _
  private var writer: MySQLDataWriter = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mysql.start()

    val host    = mysql.getHost
    val port    = mysql.getMappedPort(3306)
    val rootPwd = mysql.getPassword

    // 用 root 建库建表 + 给 test 用户授 REPLICATION 权限（Debezium 必需）
    val adminUrl = s"jdbc:mysql://$host:$port/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    Using.resource(DriverManager.getConnection(adminUrl, "root", rootPwd)) { conn =>
      val stmt = conn.createStatement()
      stmt.execute("CREATE DATABASE IF NOT EXISTS target_db")
      stmt.execute(
        "CREATE TABLE source_db.orders (id INT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))")
      stmt.execute(
        "CREATE TABLE target_db.orders (id INT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))")
      // Debezium MySQL connector 需要的最低权限集合
      stmt.execute("GRANT REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'test'@'%'")
      stmt.execute("GRANT ALL PRIVILEGES ON target_db.* TO 'test'@'%'")
      stmt.execute("FLUSH PRIVILEGES")
    }

    sourceDbConfig = makeDbConfig(host, port, "source_db", isSource = true)
    targetDbConfig = makeDbConfig(host, port, "target_db", isSource = false)

    // DebeziumBinlogReader.buildDebeziumConfig 用相对路径 data/offsets/$task.dat
    // 提前确保目录存在（sbt fork 的 cwd = 项目根）
    Files.createDirectories(Paths.get("data/offsets"))
    Files.createDirectories(Paths.get("data/schema-history"))
  }

  private def makeDbConfig(host: String, port: Int, database: String, isSource: Boolean): DatabaseConfig = {
    val pool = ConnectionPoolConfig(maxPoolSize = 5, minIdle = 1, connectionTimeout = 10.seconds)
    val deb =
      if (isSource)
        DebeziumConfig(
          snapshotMode     = "never",            // 只测增量 CDC
          tableIncludeList = "source_db.orders",
          maxBatchSize     = 100,
          maxQueueSize     = 1000,
          errorsMaxRetries = 1,
          pollIntervalMs   = 200
        )
      else
        DebeziumConfig() // target 不用 Debezium config，给默认值
    DatabaseConfig(
      host           = host,
      port           = port,
      username       = "test",
      password       = "test",
      database       = database,
      connectionPool = pool,
      debeziumConfig = deb
    )
  }

  override def afterAll(): Unit = {
    try if (pipeline != null) pipeline.stop() catch { case _: Throwable => () }
    try if (writer != null) writer.close()    catch { case _: Throwable => () }
    try mysql.stop()                          catch { case _: Throwable => () }
    try {
      // 清理 Debezium 留下的 offset/schema-history 文件，避免污染下次测试
      Paths.get(s"data/offsets/$taskName.dat").toFile.delete()
      Paths.get(s"data/schema-history/$taskName.dat").toFile.delete()
      deleteRecursive(tempDataDir)
    } catch { case _: Throwable => () }
    try system.terminate() catch { case _: Throwable => () }
    super.afterAll()
  }

  private def deleteRecursive(p: Path): Unit = {
    if (Files.exists(p)) {
      if (Files.isDirectory(p)) {
        Files.list(p).forEach(deleteRecursive)
      }
      Files.deleteIfExists(p)
    }
  }

  "Real Debezium pipeline" should {

    "replicate INSERTs source→target and drain AckRegistry to zero after commit (核心：ack 闭环真生效)" in {

      val cdcConfig = CDCConfig(
        taskName    = taskName,
        sourceType  = "mysql",
        targetType  = "mysql",
        source      = sourceDbConfig,
        target      = targetDbConfig,
        metadata    = sourceDbConfig, // FileOffsetStore 不用 metadata
        filter      = FilterConfig(),
        parallelism = ParallelismConfig(
          partitionCount = 4,
          batchSize      = 10,
          flushInterval  = 500.millis
        ),
        offset = OffsetConfig(
          // config.FileOffsetStore（OffsetStoreType case object），不是 coordinator.FileOffsetStore（class）
          storeType      = cn.xuyinyin.cdc.config.FileOffsetStore,
          commitInterval = 1.second,
          storeConfig    = Map("path" -> s"$tempDataDir/offsets/coordinator.txt")
        )
      )

      // 装配 pipeline 各组件（手动 wire，等价于 CDCEngine 内部做的事）
      val offsetStore       = new FileOffsetStore(s"$tempDataDir/offsets/coordinator.txt")
      val offsetCoordinator = DefaultOffsetCoordinator(cdcConfig.parallelism.partitionCount, offsetStore)

      val catalog    = new MySQLCatalogService(sourceDbConfig)
      val normalizer = new DebeziumEventNormalizer(catalog, "source_db")
      reader = new DebeziumBinlogReader(sourceDbConfig, taskName, bufferSize = 1000)

      val targetConnMgr = JdbcConnectionManager.forMySQL(targetDbConfig, ConnectorConfig.empty)
      writer = new MySQLDataWriter(targetConnMgr, "target_db", ConnectorConfig.empty)

      val router = new HashBasedRouter(cdcConfig.parallelism.partitionCount)
      val workers = (0 until cdcConfig.parallelism.partitionCount).map { p =>
        new DefaultApplyWorker(p, writer, offsetCoordinator, cdcConfig.parallelism.batchSize, None)
      }

      pipeline = new CDCStreamPipeline(cdcConfig, reader, normalizer, router, workers, offsetCoordinator)

      // 启动 pipeline（异步：返回 Future 但我们不 await）
      pipeline.run(FilePosition("", 0))

      // 等 Debezium engine 真正初始化好（snapshot.mode=never，但仍需要建立连接 + 读 schema）
      // 注：写在 sleep 之前的事件会被丢失（current pos 之前），所以必须等到这里再 INSERT
      Thread.sleep(8000)

      // INSERT N 行到 source（这些事件应被 Debezium 捕获并复制到 target）
      val N = 20
      val sourceUrl = jdbcUrl(sourceDbConfig.host, sourceDbConfig.port, "source_db")
      Using.resource(DriverManager.getConnection(sourceUrl, "test", "test")) { conn =>
        val ps = conn.prepareStatement("INSERT INTO orders (id, name, amount) VALUES (?, ?, ?)")
        (1 to N).foreach { i =>
          ps.setInt(1, i)
          ps.setString(2, s"order-$i")
          ps.setBigDecimal(3, java.math.BigDecimal.valueOf(i.toLong * 100L))
          ps.executeUpdate()
        }
      }

      val targetUrl = jdbcUrl(targetDbConfig.host, targetDbConfig.port, "target_db")

      // [副断言 1] target 行数追上 source
      eventually {
        rowCount(targetUrl, "orders") shouldBe N
      }

      // [核心断言 M2.2] commit 成功后 AckRegistry 回调表清零
      // 这证明 commitAndAck 真的调用了 dbr.ack(recordId)，没有泄漏。
      eventually {
        reader.ackPendingCount() shouldBe 0
      }

      // [副断言 2] OffsetCoordinator 已落 commit checkpoint
      offsetCoordinator.getLastCommittedPosition() shouldBe defined

      // [M2.3] source/target 内容 MD5 一致（包括字段顺序、值精度）
      val sourceSum = checksum(sourceUrl, "orders")
      val targetSum = checksum(targetUrl, "orders")
      sourceSum shouldBe targetSum
      sourceSum should not be empty
    }
  }

  // ---- helpers ----

  private def jdbcUrl(host: String, port: Int, db: String): String =
    s"jdbc:mysql://$host:$port/$db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

  private def rowCount(url: String, table: String): Int = {
    Using.resource(DriverManager.getConnection(url, "test", "test")) { conn =>
      val rs = conn.createStatement().executeQuery(s"SELECT COUNT(*) FROM $table")
      rs.next()
      rs.getInt(1)
    }
  }

  /** 计算表内容的 MD5：对 (id|name|amount) 按 id 排序拼接后 MD5。 */
  private def checksum(url: String, table: String): String = {
    Using.resource(DriverManager.getConnection(url, "test", "test")) { conn =>
      val rs = conn.createStatement().executeQuery(
        s"SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', id, name, amount) ORDER BY id SEPARATOR ';')) FROM $table")
      rs.next()
      Option(rs.getString(1)).getOrElse("")
    }
  }
}
