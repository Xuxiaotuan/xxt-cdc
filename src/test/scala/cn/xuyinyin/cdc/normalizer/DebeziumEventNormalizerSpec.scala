package cn.xuyinyin.cdc.normalizer

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.model._
import cn.xuyinyin.cdc.reader.{DeleteRowsEvent, RawBinlogEvent, UpdateRowsEvent, WriteRowsEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * DebeziumEventNormalizer 集成测试
 *
 * 覆盖锐评要求的 5 个最小测试：
 * 1. Debezium INSERT JSON → ChangeEvent
 * 2. Debezium UPDATE JSON → ChangeEvent
 * 3. Debezium DELETE JSON → ChangeEvent
 * 4+5 见 CDCStreamPipeline 测试（Apply失败不ack / Apply成功才ack）
 */
class DebeziumEventNormalizerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Mock CatalogService
  val mockCatalog = new CatalogService {
    override def getTableSchema(table: TableId): Future[TableSchema] = {
      Future.successful(TableSchema(
        tableId = table,
        columns = Seq(
          ColumnMeta("id", Int, nullable = false, defaultValue = None),
          ColumnMeta("name", VarChar(255), nullable = true, defaultValue = None),
          ColumnMeta("email", VarChar(255), nullable = true, defaultValue = None)
        ),
        primaryKeys = Seq("id"),
        indexes = Seq.empty
      ))
    }
    override def discoverTables(config: cn.xuyinyin.cdc.config.FilterConfig): Future[Seq[TableMeta]] =
      Future.successful(Seq.empty)
    override def validateBinlogConfig(source: cn.xuyinyin.cdc.config.DatabaseConfig): Future[cn.xuyinyin.cdc.catalog.BinlogCapability] =
      Future.successful(cn.xuyinyin.cdc.catalog.BinlogCapability(enabled = true, format = "ROW", rowImage = "FULL", gtidEnabled = true))
  }

  val normalizer = new DebeziumEventNormalizer(mockCatalog, "test_db")
  val testTimestamp = Instant.parse("2026-05-10T02:00:00Z")

  // ====== 测试 1: INSERT JSON → ChangeEvent ======

  "DebeziumEventNormalizer" should "parse Debezium INSERT JSON into ChangeEvent" in {
    val insertJson =
      """{
        |  "op": "c",
        |  "ts_ms": 1746849600000,
        |  "before": null,
        |  "after": {"id": 1, "name": "Alice", "email": "alice@test.com"},
        |  "source": {
        |    "db": "test_db",
        |    "table": "users",
        |    "file": "binlog.000001",
        |    "pos": 1234,
        |    "gtid": ""
        |  }
        |}""".stripMargin

    val rawEvent = RawBinlogEvent(
      recordId = "test-record-1",
      position = FilePosition("binlog.000001", 1234L),
      timestamp = testTimestamp,
      eventType = WriteRowsEvent,
      tableId = Some(TableId("test_db", "users")),
      rawData = insertJson
    )

    val result = normalizer.normalize(rawEvent)

    result shouldBe defined
    val event = result.get
    event.tableId.database shouldBe "test_db"
    event.tableId.table shouldBe "users"
    event.operation shouldBe Insert
    event.after shouldBe defined
    event.after.get("id") shouldBe 1
    event.after.get("name") shouldBe "Alice"
    event.after.get("email") shouldBe "alice@test.com"
    event.before shouldBe None
    event.primaryKey should not be empty
    event.primaryKey.get("id") shouldBe Some(1)
  }

  // ====== 测试 2: UPDATE JSON → ChangeEvent ======

  "DebeziumEventNormalizer" should "parse Debezium UPDATE JSON with before/after" in {
    val updateJson =
      """{
        |  "op": "u",
        |  "ts_ms": 1746849600000,
        |  "before": {"id": 1, "name": "Alice", "email": "old@test.com"},
        |  "after": {"id": 1, "name": "Alice Updated", "email": "new@test.com"},
        |  "source": {
        |    "db": "test_db",
        |    "table": "users",
        |    "file": "binlog.000001",
        |    "pos": 5678
        |  }
        |}""".stripMargin

    val rawEvent = RawBinlogEvent(
      recordId = "test-record-1",
      position = FilePosition("binlog.000001", 5678L),
      timestamp = testTimestamp,
      eventType = UpdateRowsEvent,
      tableId = Some(TableId("test_db", "users")),
      rawData = updateJson
    )

    val result = normalizer.normalize(rawEvent)

    result shouldBe defined
    val event = result.get
    event.operation shouldBe Update
    event.before shouldBe defined
    event.before.get("name") shouldBe "Alice"
    event.before.get("email") shouldBe "old@test.com"
    event.after shouldBe defined
    event.after.get("name") shouldBe "Alice Updated"
    event.after.get("email") shouldBe "new@test.com"
  }

  // ====== 测试 3: DELETE JSON → ChangeEvent ======

  "DebeziumEventNormalizer" should "parse Debezium DELETE JSON with before data" in {
    val deleteJson =
      """{
        |  "op": "d",
        |  "ts_ms": 1746849600000,
        |  "before": {"id": 1, "name": "Alice", "email": "alice@test.com"},
        |  "after": null,
        |  "source": {
        |    "db": "test_db",
        |    "table": "users",
        |    "file": "binlog.000002",
        |    "pos": 9999
        |  }
        |}""".stripMargin

    val rawEvent = RawBinlogEvent(
      recordId = "test-record-1",
      position = FilePosition("binlog.000002", 9999L),
      timestamp = testTimestamp,
      eventType = DeleteRowsEvent,
      tableId = Some(TableId("test_db", "users")),
      rawData = deleteJson
    )

    val result = normalizer.normalize(rawEvent)

    result shouldBe defined
    val event = result.get
    event.operation shouldBe Delete
    event.before shouldBe defined
    event.before.get("id") shouldBe 1
    event.after shouldBe None
  }

  // ====== 附加测试：过滤非源数据库事件 ======

  "DebeziumEventNormalizer" should "filter out events from non-source database" in {
    val json = """{"op":"c","after":{"id":1},"source":{"db":"other_db","table":"foo"}}"""
    val rawEvent = RawBinlogEvent(
      recordId = "test-record-1",
      position = FilePosition("", 0L),
      timestamp = testTimestamp,
      eventType = WriteRowsEvent,
      tableId = Some(TableId("other_db", "foo")),
      rawData = json
    )
    normalizer.normalize(rawEvent) shouldBe None
  }

  // ====== 附加测试：非数据操作跳过 ======

  "DebeziumEventNormalizer" should "skip non-data operations (heartbeat/schema)" in {
    val json = """{"op":"h","source":{"db":"test_db","table":"users"}}"""
    val rawEvent = RawBinlogEvent(
      recordId = "test-record-1",
      position = FilePosition("", 0L),
      timestamp = testTimestamp,
      eventType = WriteRowsEvent,
      tableId = Some(TableId("test_db", "users")),
      rawData = json
    )
    normalizer.normalize(rawEvent) shouldBe None
  }

  // ====== 附加测试：非 JSON String 输入降级处理 ======

  "DebeziumEventNormalizer" should "return None for non-String rawData" in {
    val rawEvent = RawBinlogEvent(
      recordId = "test-record-1",
      position = FilePosition("", 0L),
      timestamp = testTimestamp,
      eventType = WriteRowsEvent,
      tableId = Some(TableId("test_db", "users")),
      rawData = 42 // Int instead of String
    )
    normalizer.normalize(rawEvent) shouldBe None
  }
}
