package cn.xuyinyin.cdc.filter

import cn.xuyinyin.cdc.config.FilterConfig
import cn.xuyinyin.cdc.model.TableId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TableFilterSpec extends AnyFlatSpec with Matchers {

  "TableFilter" should "include tables that match database and table include rules" in {
    val filter = new TableFilter(
      FilterConfig(
        includeDatabases = Seq("source_db"),
        includeTablePatterns = Seq("users", "orders_*")
      )
    )

    filter.shouldInclude(TableId("source_db", "users")) shouldBe true
    filter.shouldInclude(TableId("source_db", "orders_2026")) shouldBe true
    filter.shouldInclude(TableId("other_db", "users")) shouldBe false
    filter.shouldInclude(TableId("source_db", "payments")) shouldBe false
  }

  it should "exclude tables when exclude rules match" in {
    val filter = new TableFilter(
      FilterConfig(
        includeDatabases = Seq("source_db"),
        excludeTablePatterns = Seq("temp_*", "*_backup")
      )
    )

    filter.shouldInclude(TableId("source_db", "users")) shouldBe true
    filter.shouldInclude(TableId("source_db", "temp_users")) shouldBe false
    filter.shouldInclude(TableId("source_db", "users_backup")) shouldBe false
  }

  it should "report conflicting database include and exclude rules" in {
    val filter = new TableFilter(
      FilterConfig(
        includeDatabases = Seq("source_db"),
        excludeDatabases = Seq("source_db")
      )
    )

    val result = filter.validateConfig()
    result.isValid shouldBe false
    result.errors.mkString("\n") should include("source_db")
  }
}
