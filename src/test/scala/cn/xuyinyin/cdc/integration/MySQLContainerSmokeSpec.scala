package cn.xuyinyin.cdc.integration

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager

/**
 * Testcontainers MySQL 冒烟测试。
 *
 * 验证基础设施能力：
 *   1. Docker 可达
 *   2. MySQL 容器能起来
 *   3. JDBC 连接可用
 *   4. 容器开启了 binlog（ROW 格式）—— Debezium 真测试的前置条件
 *
 * 这是 P1 真 Debezium 测试的最小前置依赖；冒烟过了再写 Debezium spec。
 */
class MySQLContainerSmokeSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  // 用 8.0 版本（Debezium MySQL Connector 3.0 兼容范围）+ binlog ROW 格式
  // 注意：Java self-bounded 泛型 `MySQLContainer<SELF extends MySQLContainer<SELF>>`
  //       在 Scala 2.13 fluent 链式调用时类型推断失败，改用过程式 setter
  private val mysql: MySQLContainer[_] = {
    val c = new MySQLContainer(DockerImageName.parse("mysql:8.0"))
    c.withDatabaseName("smoke_test")
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

  override def beforeAll(): Unit = {
    super.beforeAll()
    mysql.start()
  }

  override def afterAll(): Unit = {
    try mysql.stop()
    catch { case _: Throwable => () }
    super.afterAll()
  }

  "MySQL testcontainer" should {

    "be reachable via JDBC" in {
      val conn = DriverManager.getConnection(mysql.getJdbcUrl, mysql.getUsername, mysql.getPassword)
      try {
        val rs    = conn.createStatement().executeQuery("SELECT 1")
        rs.next() shouldBe true
        rs.getInt(1) shouldBe 1
      } finally conn.close()
    }

    "have binlog enabled with ROW format" in {
      val conn = DriverManager.getConnection(mysql.getJdbcUrl, mysql.getUsername, mysql.getPassword)
      try {
        val stmt = conn.createStatement()

        // log_bin = ON
        val rs1 = stmt.executeQuery("SHOW VARIABLES LIKE 'log_bin'")
        rs1.next() shouldBe true
        rs1.getString("Value") shouldBe "ON"

        // binlog_format = ROW
        val rs2 = stmt.executeQuery("SHOW VARIABLES LIKE 'binlog_format'")
        rs2.next() shouldBe true
        rs2.getString("Value") shouldBe "ROW"

        // gtid_mode = ON
        val rs3 = stmt.executeQuery("SHOW VARIABLES LIKE 'gtid_mode'")
        rs3.next() shouldBe true
        rs3.getString("Value") shouldBe "ON"
      } finally conn.close()
    }

    "support DDL + DML round-trip" in {
      val conn = DriverManager.getConnection(mysql.getJdbcUrl, mysql.getUsername, mysql.getPassword)
      try {
        val stmt = conn.createStatement()
        stmt.execute("CREATE TABLE smoke_orders (id INT PRIMARY KEY, name VARCHAR(64))")
        stmt.execute("INSERT INTO smoke_orders VALUES (1, 'alpha'), (2, 'beta')")

        val rs = stmt.executeQuery("SELECT COUNT(*) FROM smoke_orders")
        rs.next() shouldBe true
        rs.getInt(1) shouldBe 2
      } finally conn.close()

      // 验证 binlog 在写：SHOW MASTER STATUS 需要 REPLICATION CLIENT/SUPER 权限。
      // testcontainers 的 test 用户没有该权限，改用 root（密码与 test 用户一致）。
      // MySQL 8.0 用 SHOW MASTER STATUS；8.4+ 才改名为 SHOW BINARY LOG STATUS。
      val rootConn = DriverManager.getConnection(mysql.getJdbcUrl, "root", mysql.getPassword)
      try {
        val rsMaster = rootConn.createStatement().executeQuery("SHOW MASTER STATUS")
        rsMaster.next() shouldBe true
        Option(rsMaster.getString("File")).getOrElse("") should not be empty
      } finally rootConn.close()
    }
  }
}
