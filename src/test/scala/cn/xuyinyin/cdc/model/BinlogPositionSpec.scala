package cn.xuyinyin.cdc.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BinlogPositionSpec extends AnyFlatSpec with Matchers {

  "BinlogPosition" should "round-trip file positions" in {
    val position = FilePosition("mysql-bin.000123", 456789L)

    position.asString shouldBe "mysql-bin.000123:456789"
    BinlogPosition.parse(position.asString) shouldBe Some(position)
  }

  it should "round-trip GTID positions" in {
    val position = GTIDPosition("3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5")

    position.asString shouldBe "GTID:3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5"
    BinlogPosition.parse(position.asString) shouldBe Some(position)
  }

  it should "return None for invalid position strings" in {
    BinlogPosition.parse("") shouldBe None
    BinlogPosition.parse("mysql-bin.000001:not-a-number") shouldBe None
    BinlogPosition.parse("too:many:parts") shouldBe None
  }
}
