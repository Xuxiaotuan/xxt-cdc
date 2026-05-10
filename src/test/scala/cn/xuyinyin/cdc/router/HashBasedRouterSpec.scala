package cn.xuyinyin.cdc.router

import cn.xuyinyin.cdc.model.{ChangeEvent, FilePosition, Insert, TableId, Update}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class HashBasedRouterSpec extends AnyFlatSpec with Matchers {

  private def event(table: String, pk: Map[String, Any], position: Long): ChangeEvent =
    ChangeEvent(
      recordId = s"test-$table-$position",
      tableId = TableId("source_db", table),
      operation = Insert,
      primaryKey = pk,
      before = None,
      after = Some(pk),
      timestamp = Instant.EPOCH,
      position = FilePosition("mysql-bin.000001", position)
    )

  "HashBasedRouter" should "route the same table and primary key to the same partition" in {
    val router = new HashBasedRouter(partitionCount = 16)

    val first = event("users", Map("id" -> 1), 100L)
    val second = first.copy(operation = Update, position = FilePosition("mysql-bin.000001", 200L))

    router.route(first) shouldBe router.route(second)
  }

  it should "always return a partition within range" in {
    val router = new HashBasedRouter(partitionCount = 8)

    val partitions = (1 to 1000).map { id =>
      router.route(event("orders", Map("id" -> id), id.toLong))
    }

    all(partitions) should be >= 0
    all(partitions) should be < 8
  }

  it should "reject non-positive partition counts" in {
    an[IllegalArgumentException] shouldBe thrownBy {
      new HashBasedRouter(partitionCount = 0)
    }
  }
}
