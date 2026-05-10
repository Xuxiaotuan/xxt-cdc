package cn.xuyinyin.cdc.cluster

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * 用 H2 in-memory（MySQL 兼容模式）覆盖 [[MySQLSingletonLock]] 的关键路径：
 *   - acquire 空表 → 成功（INSERT 路径）
 *   - acquire 自己持有 → 成功（UPDATE 续约路径）
 *   - acquire 别人持有未过期 → 失败
 *   - acquire 别人持有已过期 → 成功（UPDATE 抢占路径）
 *   - renew / release / currentHolder 语义
 *   - 并发 acquire 仅一人赢
 *
 * 真 MySQL 行为 ≈ H2 MySQL 模式 + PRIMARY KEY 串行化；并发原子性靠 PK 保障。
 */
class MySQLSingletonLockSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterEach {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  // 每个测试一个独立 DB（避免相互污染）
  private var dbName: String  = _
  private var jdbcUrl: String = _

  override def beforeEach(): Unit = {
    dbName = s"test_${UUID.randomUUID().toString.replace("-", "")}"
    // MODE=MySQL 让 H2 兼容 MySQL 函数；DB_CLOSE_DELAY=-1 保留连接关闭后的内存数据
    jdbcUrl = s"jdbc:h2:mem:$dbName;MODE=MySQL;DB_CLOSE_DELAY=-1"
    // 显式确保 H2 driver 加载
    Class.forName("org.h2.Driver")
  }

  override def afterEach(): Unit = {
    // 释放 H2 in-memory DB
    try {
      val conn = DriverManager.getConnection(jdbcUrl, "", "")
      try {
        conn.createStatement().execute("DROP ALL OBJECTS DELETE FILES")
      } finally conn.close()
    } catch { case _: Throwable => () }
  }

  private def newLock(table: String = "cdc_singleton_lock"): MySQLSingletonLock =
    MySQLSingletonLock.fromJdbcUrl(jdbcUrl, tableName = table)

  private def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  "MySQLSingletonLock" should {

    "acquire on empty table (INSERT path)" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 10.seconds)) shouldBe true
      await(lock.currentHolder("task-A")) shouldBe Some("node-1")
    }

    "acquire by same holder (renew via UPDATE)" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 5.seconds)) shouldBe true
      await(lock.acquire("task-A", "node-1", 30.seconds)) shouldBe true
      await(lock.currentHolder("task-A")) shouldBe Some("node-1")
    }

    "refuse acquire when held by another (not expired)" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 30.seconds)) shouldBe true
      await(lock.acquire("task-A", "node-2", 30.seconds)) shouldBe false
      await(lock.currentHolder("task-A")) shouldBe Some("node-1")
    }

    "allow acquire after another holder's lock expires (seize path)" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 200.millis)) shouldBe true
      Thread.sleep(400) // 等过期
      await(lock.acquire("task-A", "node-2", 30.seconds)) shouldBe true
      await(lock.currentHolder("task-A")) shouldBe Some("node-2")
    }

    "renew only when held by self" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 10.seconds)) shouldBe true
      await(lock.renew("task-A", "node-1", 30.seconds)) shouldBe true
      await(lock.renew("task-A", "node-2", 30.seconds)) shouldBe false
      await(lock.currentHolder("task-A")) shouldBe Some("node-1")
    }

    "release only when held by self" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 10.seconds)) shouldBe true

      // 别人 release 不动
      await(lock.release("task-A", "node-2"))
      await(lock.currentHolder("task-A")) shouldBe Some("node-1")

      // 自己 release 后清空
      await(lock.release("task-A", "node-1"))
      await(lock.currentHolder("task-A")) shouldBe None
    }

    "currentHolder returns None for expired lock" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 200.millis)) shouldBe true
      Thread.sleep(400)
      await(lock.currentHolder("task-A")) shouldBe None
    }

    "isolate locks across different taskIds" in {
      val lock = newLock()
      await(lock.acquire("task-A", "node-1", 10.seconds)) shouldBe true
      await(lock.acquire("task-B", "node-2", 10.seconds)) shouldBe true
      await(lock.currentHolder("task-A")) shouldBe Some("node-1")
      await(lock.currentHolder("task-B")) shouldBe Some("node-2")
    }

    "elect a single winner under concurrent acquire" in {
      val lock = newLock()
      val attempts = 8

      val futures: Seq[Future[Boolean]] = (1 to attempts).map { i =>
        lock.acquire("task-X", s"node-$i", 30.seconds)
      }
      val results = await(Future.sequence(futures))

      // 并发场景下只允许一个 acquire 返回 true（INSERT 唯一胜者，
      // 或同一节点重复抢占自己的锁也算赢；本测试每个 holder 不同，故仅一个赢）
      results.count(_ == true) shouldBe 1

      // 表中确实有一个持有者
      await(lock.currentHolder("task-X")).isDefined shouldBe true
    }
  }
}
