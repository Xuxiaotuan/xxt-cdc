package cn.xuyinyin.cdc.reader

import cn.xuyinyin.cdc.config.{ConnectionPoolConfig, DatabaseConfig, DebeziumConfig}
import cn.xuyinyin.cdc.model.{FilePosition, GTIDPosition}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Debezium Binlog Reader 单元测试
 * 
 * 测试目标：
 * 1. 验证 Debezium 配置构建的正确性
 * 2. 验证 JSON 解析功能（操作类型、表信息、位置、时间戳）
 * 3. 验证事件类型映射
 * 4. 验证错误处理和边界条件
 * 
 * 注意：这些测试不需要真实的 MySQL 连接，主要测试纯函数逻辑
 */
class DebeziumBinlogReaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystem("DebeziumBinlogReaderSpec")
  implicit val mat: Materializer = Materializer(system)
  implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = {
    system.terminate()
  }
  // 测试用的数据库配置
  val testConfig: DatabaseConfig = DatabaseConfig(
    host = "localhost",
    port = 3306,
    username = "cdc_user",
    password = "cdc_password",
    database = "source_db",
    connectionPool = ConnectionPoolConfig(
      maxPoolSize = 10,
      minIdle = 2,
      connectionTimeout = 30.seconds
    ),
    debeziumConfig = DebeziumConfig(
      snapshotMode = "never",
      maxBatchSize = 2048,
      maxQueueSize = 8192,
      errorsMaxRetries = 3,
      pollIntervalMs = 1000,
      tableIncludeList = ""
    )
  )

  // 创建测试用的 reader
  val reader = new DebeziumBinlogReader(testConfig, "test-task", 1000)

  // 使用反射访问私有方法进行测试
  def callPrivateMethod[T](obj: Any, methodName: String, paramTypes: Class[_]*)(args: Any*): T = {
    val method = obj.getClass.getDeclaredMethod(methodName, paramTypes: _*)
    method.setAccessible(true)
    method.invoke(obj, args.map(_.asInstanceOf[AnyRef]): _*).asInstanceOf[T]
  }

  // ==================== JSON 解析测试 ====================

  "DebeziumBinlogReader" should "从 JSON 中正确提取操作类型 (op)" in {
    // 测试目的：验证能否正确识别 INSERT、UPDATE、DELETE、READ 操作
    val insertJson = """{"op":"c","after":{"id":1}}"""
    val updateJson = """{"op":"u","before":{"id":1},"after":{"id":1}}"""
    val deleteJson = """{"op":"d","before":{"id":1}}"""
    val readJson = """{"op":"r","after":{"id":1}}"""

    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(insertJson) shouldBe Some("c")
    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(updateJson) shouldBe Some("u")
    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(deleteJson) shouldBe Some("d")
    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(readJson) shouldBe Some("r")
  }

  it should "从 JSON 中正确提取表信息 (database.table)" in {
    // 测试目的：验证能否从 Debezium 事件中提取数据库名和表名
    val json = """{"source":{"db":"test","table":"users"}}"""
    
    val result = callPrivateMethod[Option[(String, String)]](reader, "extractTableInfo", classOf[String])(json)
    result shouldBe Some(("test", "users"))
  }

  it should "从 JSON 中正确提取 File Position (binlog 文件名和位置)" in {
    // 测试目的：验证能否提取 binlog 文件位置信息
    val json = """{"source":{"file":"mysql-bin.000001","pos":12345}}"""
    
    val position = callPrivateMethod[cn.xuyinyin.cdc.model.BinlogPosition](
      reader, "extractPositionFromValue", classOf[String]
    )(json)
    
    position shouldBe a[FilePosition]
    position.asInstanceOf[FilePosition].filename shouldBe "mysql-bin.000001"
    position.asInstanceOf[FilePosition].position shouldBe 12345L
  }

  it should "从 JSON 中正确提取 GTID Position" in {
    // 测试目的：验证能否提取 GTID 位置信息（用于 GTID 模式）
    val json = """{"source":{"gtid":"3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5"}}"""
    
    val position = callPrivateMethod[cn.xuyinyin.cdc.model.BinlogPosition](
      reader, "extractPositionFromValue", classOf[String]
    )(json)
    
    position shouldBe a[GTIDPosition]
    position.asInstanceOf[GTIDPosition].gtidSet shouldBe "3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5"
  }

  it should "从 JSON 中正确提取时间戳 (ts_ms)" in {
    // 测试目的：验证能否提取事件时间戳并转换为 Instant
    val json = """{"ts_ms":1609459200000}"""
    
    val timestamp = callPrivateMethod[Option[java.time.Instant]](
      reader, "extractTimestamp", classOf[String]
    )(json)
    
    timestamp shouldBe defined
    timestamp.get.toEpochMilli shouldBe 1609459200000L
  }

  // ==================== 事件类型映射测试 ====================

  it should "正确映射操作类型到 BinlogEventType" in {
    // 测试目的：验证 Debezium 操作符能正确映射到内部事件类型
    // c -> INSERT, u -> UPDATE, d -> DELETE, r -> READ(快照)
    callPrivateMethod[BinlogEventType](reader, "mapOperationType", classOf[String])("c") shouldBe WriteRowsEvent
    callPrivateMethod[BinlogEventType](reader, "mapOperationType", classOf[String])("u") shouldBe UpdateRowsEvent
    callPrivateMethod[BinlogEventType](reader, "mapOperationType", classOf[String])("d") shouldBe DeleteRowsEvent
    callPrivateMethod[BinlogEventType](reader, "mapOperationType", classOf[String])("r") shouldBe WriteRowsEvent
  }

  // ==================== 工具方法测试 ====================

  it should "生成有效的服务器 ID (5000-65000 范围)" in {
    // 测试目的：验证生成的服务器 ID 在合理范围内，避免与其他 MySQL 实例冲突
    val serverId = callPrivateMethod[Long](reader, "generateServerId")()
    serverId should be >= 5000L
    serverId should be <= 65000L
  }

  // ==================== 配置构建测试 ====================

  it should "使用 File Position 构建正确的 Debezium 配置" in {
    // 测试目的：验证基于文件位置的配置是否包含所有必需参数
    val position = FilePosition("mysql-bin.000001", 12345L)
    
    val props = callPrivateMethod[java.util.Properties](
      reader, "buildDebeziumConfig", classOf[cn.xuyinyin.cdc.model.BinlogPosition]
    )(position)
    
    props.getProperty("connector.class") shouldBe "io.debezium.connector.mysql.MySqlConnector"
    props.getProperty("database.hostname") shouldBe "localhost"
    props.getProperty("database.port") shouldBe "3306"
    props.getProperty("database.user") shouldBe "cdc_user"
    props.getProperty("database.include.list") shouldBe "source_db"
    props.getProperty("snapshot.mode") shouldBe "never"
    props.getProperty("max.batch.size") shouldBe "2048"
    props.getProperty("max.queue.size") shouldBe "8192"
  }

  it should "使用 GTID Position 构建正确的 Debezium 配置" in {
    // 测试目的：验证 GTID 模式的配置是否正确设置
    val position = GTIDPosition("3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5")
    
    val props = callPrivateMethod[java.util.Properties](
      reader, "buildDebeziumConfig", classOf[cn.xuyinyin.cdc.model.BinlogPosition]
    )(position)
    
    props.getProperty("gtid.source.includes") shouldBe "3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5"
    props.getProperty("database.history.skip.unparseable.ddl") shouldBe "true"
  }

  it should "使用表过滤配置构建正确的 Debezium 配置" in {
    // 测试目的：验证表过滤功能是否正确配置
    val configWithFilter = testConfig.copy(
      debeziumConfig = testConfig.debeziumConfig.copy(
        tableIncludeList = "test.users,test.orders"
      )
    )
    val readerWithFilter = new DebeziumBinlogReader(configWithFilter, "test-filter", 1000)
    
    val props = callPrivateMethod[java.util.Properties](
      readerWithFilter, "buildDebeziumConfig", classOf[cn.xuyinyin.cdc.model.BinlogPosition]
    )(FilePosition("", 0L))
    
    props.getProperty("table.include.list") shouldBe "test.users,test.orders"
  }

  // ==================== 错误处理测试 ====================

  it should "优雅处理空 JSON" in {
    // 测试目的：验证空 JSON 不会导致异常，而是返回 None
    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])("") shouldBe None
    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])("{}") shouldBe None
  }

  it should "优雅处理格式错误的 JSON" in {
    // 测试目的：验证格式错误的 JSON 不会导致异常
    callPrivateMethod[Option[(String, String)]](reader, "extractTableInfo", classOf[String])("{invalid json}") shouldBe None
    callPrivateMethod[Option[(String, String)]](reader, "extractTableInfo", classOf[String])("""{"source":{}}""") shouldBe None
  }

  // ==================== 完整事件解析测试 ====================

  it should "正确解析完整的 INSERT 事件 JSON" in {
    // 测试目的：验证能否解析真实的 Debezium INSERT 事件
    val insertEvent =
      """{
        |  "op": "c",
        |  "source": {
        |    "db": "test",
        |    "table": "users",
        |    "file": "mysql-bin.000001",
        |    "pos": 12345,
        |    "ts_ms": 1609459200000
        |  },
        |  "after": {
        |    "id": 1,
        |    "name": "John Doe",
        |    "email": "john@example.com"
        |  }
        |}""".stripMargin

    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(insertEvent) shouldBe Some("c")
    callPrivateMethod[Option[(String, String)]](reader, "extractTableInfo", classOf[String])(insertEvent) shouldBe Some(("test", "users"))
  }

  it should "正确解析完整的 UPDATE 事件 JSON" in {
    // 测试目的：验证能否解析真实的 Debezium UPDATE 事件
    val updateEvent =
      """{
        |  "op": "u",
        |  "source": {
        |    "db": "test",
        |    "table": "users",
        |    "file": "mysql-bin.000001",
        |    "pos": 23456
        |  },
        |  "before": {
        |    "id": 1,
        |    "name": "John Doe"
        |  },
        |  "after": {
        |    "id": 1,
        |    "name": "Jane Doe"
        |  }
        |}""".stripMargin

    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(updateEvent) shouldBe Some("u")
  }

  it should "正确解析完整的 DELETE 事件 JSON" in {
    // 测试目的：验证能否解析真实的 Debezium DELETE 事件
    val deleteEvent =
      """{
        |  "op": "d",
        |  "source": {
        |    "db": "test",
        |    "table": "users",
        |    "file": "mysql-bin.000001",
        |    "pos": 34567
        |  },
        |  "before": {
        |    "id": 1,
        |    "name": "John Doe"
        |  }
        |}""".stripMargin

    callPrivateMethod[Option[String]](reader, "extractOperation", classOf[String])(deleteEvent) shouldBe Some("d")
  }

  // ==================== 状态管理测试 ====================

  it should "返回当前 binlog 位置" in {
    // 测试目的：验证能否获取当前读取位置（用于断点续传）
    val position = reader.getCurrentPosition()
    position shouldBe a[FilePosition]
  }
}
