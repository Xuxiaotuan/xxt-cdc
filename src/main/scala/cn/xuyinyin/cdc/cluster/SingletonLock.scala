package cn.xuyinyin.cdc.cluster

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * Singleton 锁接口（防 split-brain 双 master 跑同一 task）。
 *
 * ClusterSingleton 已经保证 cluster 内同时只有一个实例，但 SBR 失效或网络极端
 * 分裂时仍可能短暂双跑。该锁作为 belt-and-suspenders 防御。
 *
 * Phase 2.1：提供 [[InMemorySingletonLock]]（单 JVM 测试用）。
 * Phase 2.2：补充 `MySQLSingletonLock`（生产用，写 metadata DB `cdc_singleton_lock` 表）。
 *
 * 调用约定：
 *   - acquire 返回 true → 当前调用者已持锁
 *   - acquire 返回 false → 其它持有者尚未过期，调用方应 backoff 重试或退出
 *   - renew 返回 true → 续约成功
 *   - renew 返回 false → 锁已被他人接管或过期，应当主动停止当前任务
 */
trait SingletonLock {

  /** 抢占式获取锁（自身已持有则刷新过期时间）。 */
  def acquire(taskId: String, holderAddress: String, ttl: FiniteDuration)(implicit
    ec: ExecutionContext
  ): Future[Boolean]

  /** 续约（仅当当前持有者就是自己）。 */
  def renew(taskId: String, holderAddress: String, ttl: FiniteDuration)(implicit
    ec: ExecutionContext
  ): Future[Boolean]

  /** 释放（仅当当前持有者就是自己）。 */
  def release(taskId: String, holderAddress: String)(implicit ec: ExecutionContext): Future[Unit]

  /** 查询当前持有者（已过期则返回 None）。 */
  def currentHolder(taskId: String)(implicit ec: ExecutionContext): Future[Option[String]]
}

/**
 * 单 JVM 内存实现，仅用于测试与单进程模式。
 */
class InMemorySingletonLock extends SingletonLock {

  import InMemorySingletonLock.Entry

  private val locks = new ConcurrentHashMap[String, Entry]()

  override def acquire(taskId: String, holderAddress: String, ttl: FiniteDuration)(implicit
    ec: ExecutionContext
  ): Future[Boolean] = Future {
    val now    = Instant.now()
    val newEnt = Entry(holderAddress, now.plusMillis(ttl.toMillis))

    val winner = locks.compute(
      taskId,
      (_, existing) =>
        if (existing == null) newEnt
        else if (existing.expiresAt.isBefore(now)) newEnt
        else if (existing.holder == holderAddress) newEnt
        else existing
    )

    winner.holder == holderAddress
  }

  override def renew(taskId: String, holderAddress: String, ttl: FiniteDuration)(implicit
    ec: ExecutionContext
  ): Future[Boolean] = Future {
    val now = Instant.now()
    val updated = locks.computeIfPresent(
      taskId,
      (_, existing) =>
        if (existing.holder == holderAddress) Entry(holderAddress, now.plusMillis(ttl.toMillis))
        else existing
    )
    updated != null && updated.holder == holderAddress
  }

  override def release(taskId: String, holderAddress: String)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      locks.computeIfPresent(
        taskId,
        (_, existing) => if (existing.holder == holderAddress) null else existing
      )
      ()
    }

  override def currentHolder(taskId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    Future {
      val now = Instant.now()
      Option(locks.get(taskId)).filter(!_.expiresAt.isBefore(now)).map(_.holder)
    }
}

object InMemorySingletonLock {
  private[cluster] final case class Entry(holder: String, expiresAt: Instant)
}
