package cn.xuyinyin.cdc.coordinator

import cn.xuyinyin.cdc.model.{FilePosition, GTIDPosition}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Await

class FileOffsetStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private var tempDir: Path = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("xxt-cdc-offset-store-test")
  }

  override def afterEach(): Unit = {
    if (tempDir != null) {
      val files = tempDir.toFile.listFiles()
      if (files != null) files.foreach(_.delete())
      tempDir.toFile.delete()
    }
  }

  private def await[T](value: scala.concurrent.Future[T]): T =
    Await.result(value, 5.seconds)

  "FileOffsetStore" should "save and load file positions" in {
    val store = FileOffsetStore(tempDir.resolve("offset.txt").toString)
    val position = FilePosition("mysql-bin.000001", 12345L)

    await(store.save(position))

    await(store.load()) shouldBe Some(position)
  }

  it should "save and load GTID positions" in {
    val store = FileOffsetStore(tempDir.resolve("offset.txt").toString)
    val position = GTIDPosition("3E11FA47-71CA-11E1-9E33-C80AA9429562:1-5")

    await(store.save(position))

    await(store.load()) shouldBe Some(position)
  }

  it should "return None when no offset file exists" in {
    val store = FileOffsetStore(tempDir.resolve("missing-offset.txt").toString)

    await(store.load()) shouldBe None
  }

  it should "delete saved offsets" in {
    val store = FileOffsetStore(tempDir.resolve("offset.txt").toString)

    await(store.save(FilePosition("mysql-bin.000001", 12345L)))
    await(store.delete())

    await(store.load()) shouldBe None
  }
}
