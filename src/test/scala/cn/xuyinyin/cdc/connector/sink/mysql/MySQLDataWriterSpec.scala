package cn.xuyinyin.cdc.connector.sink.mysql

import cn.xuyinyin.cdc.connector.ConnectorConfig
import cn.xuyinyin.cdc.model.TableId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class MySQLDataWriterSpec extends AnyFlatSpec with Matchers {

  private class TestWriter(implicit ec: ExecutionContext)
      extends MySQLDataWriter(null, "target_db", ConnectorConfig.empty) {
    def insertSql(table: TableId, columns: Seq[String]): String =
      buildInsertSql(table, columns)

    def insertParams(data: Map[String, Any], columns: Seq[String]): Seq[Any] =
      getInsertParameters(data, columns)
  }

  "MySQLDataWriter" should "build idempotent insert SQL with one placeholder per column" in {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val writer = new TestWriter

    val sql = writer.insertSql(
      TableId("source_db", "users"),
      Seq("id", "username", "email")
    )

    sql should include("INSERT INTO target_db.users")
    sql should include("VALUES (?, ?, ?)")
    sql should include("ON DUPLICATE KEY UPDATE")
    sql should include("id = VALUES(id)")
    sql should include("username = VALUES(username)")
    sql should include("email = VALUES(email)")
  }

  it should "bind only the insert values when using VALUES(col) upsert syntax" in {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val writer = new TestWriter
    val columns = Seq("id", "username", "email")

    val params = writer.insertParams(
      Map("id" -> 1, "username" -> "alice", "email" -> "alice@example.com"),
      columns
    )

    params shouldBe Seq(1, "alice", "alice@example.com")
  }
}
