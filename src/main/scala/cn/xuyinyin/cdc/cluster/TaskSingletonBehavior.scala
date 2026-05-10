package cn.xuyinyin.cdc.cluster

import cn.xuyinyin.cdc.CborSerializable
import cn.xuyinyin.cdc.engine.CDCEngine
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.pattern.StatusReply

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Task Singleton Behavior：在 cluster 中作为 ClusterSingleton 托管的 typed Behavior，
 * 包装 [[CDCEngine]] 的生命周期。
 *
 * 自启动模式：Behavior spawn 时立即尝试 acquire lock + start engine，
 * 这样即使 Singleton 在节点间迁移，新节点上的 Behavior 也无需外部触发就会接管。
 *
 * 状态机：
 *   Initializing  → 启动中（acquire lock + start engine）
 *   Running       → 运行中（定期 heartbeat 续约 lock）
 *   Stopping      → 停止中（stop engine + release lock）
 *   Stopped       → 终态（Behavior 自身退出后 ClusterSingleton 会决定是否重启）
 *
 * Phase 2.1 现状：lock 默认为 None（不启用）；Phase 2.2 引入 MySQLSingletonLock 后启用。
 */
object TaskSingletonBehavior {

  // ========== 公共命令 ==========

  sealed trait Command extends CborSerializable

  /** 查询当前任务状态 */
  final case class GetStatus(replyTo: ActorRef[StatusReply[TaskStatus]]) extends Command

  /** 主动停止任务（停 engine + 释放锁 + 退出 Behavior） */
  final case class Shutdown(replyTo: ActorRef[StatusReply[Done]]) extends Command

  /** 任务状态快照 */
  final case class TaskStatus(
    taskId: String,
    state: String,
    holder: String,
    engineState: Option[String]
  ) extends CborSerializable

  // ========== 内部命令（不对外暴露） ==========

  private case object StartEngine extends Command

  private final case class LockAcquired(success: Boolean)         extends Command
  private final case class LockAcquireFailed(ex: Throwable)        extends Command
  private final case class EngineStarted(engine: CDCEngine)        extends Command
  private final case class EngineStartFailed(ex: Throwable)        extends Command
  private case object Heartbeat                                    extends Command
  private final case class HeartbeatResult(success: Boolean)       extends Command
  private final case class EngineStopped(replyTo: ActorRef[StatusReply[Done]])  extends Command
  private final case class EngineStopFailed(ex: Throwable, replyTo: ActorRef[StatusReply[Done]]) extends Command

  /**
   * @param taskId        任务 ID（也作为 Singleton 实例名）
   * @param engineFactory 创建 [[CDCEngine]] 的工厂；每次 Behavior spawn 时调用一次
   * @param lockOpt       可选 SingletonLock（生产环境注入 MySQLSingletonLock）
   * @param lockTtl       锁 TTL（必须 > heartbeatInterval × 3）
   * @param heartbeatInterval 心跳续约间隔
   * @param maxLockAcquireRetries lock 被占时的重试次数（防止老节点还在释放锁时新节点立即放弃）
   * @param lockRetryBackoff      重试间隔（建议 ≥ lockTtl/3，让老锁有机会过期）
   */
  def apply(
    taskId: String,
    engineFactory: () => CDCEngine,
    lockOpt: Option[SingletonLock] = None,
    lockTtl: FiniteDuration = 30.seconds,
    heartbeatInterval: FiniteDuration = 10.seconds,
    maxLockAcquireRetries: Int = 6,
    lockRetryBackoff: FiniteDuration = 10.seconds
  ): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup[Command] { context =>
        val holderAddress = context.system.address.toString
        context.log.info(s"[$taskId] Singleton spawned at $holderAddress, initializing")

        // 自启动：spawn 后立即触发 StartEngine
        context.self ! StartEngine
        initializing(
          taskId, engineFactory, lockOpt, lockTtl, heartbeatInterval,
          holderAddress, timers, context,
          retriesRemaining = maxLockAcquireRetries,
          retryBackoff     = lockRetryBackoff
        )
      }
    }
  }

  // ========== 状态：Initializing ==========

  private def initializing(
    taskId: String,
    engineFactory: () => CDCEngine,
    lockOpt: Option[SingletonLock],
    lockTtl: FiniteDuration,
    heartbeatInterval: FiniteDuration,
    holderAddress: String,
    timers: TimerScheduler[Command],
    context: ActorContext[Command],
    retriesRemaining: Int,
    retryBackoff: FiniteDuration
  ): Behavior[Command] = Behaviors.receiveMessage {

    case StartEngine =>
      implicit val ec: ExecutionContext = context.executionContext

      // Step 1: acquire lock（如有）
      val lockFut: Future[Boolean] = lockOpt match {
        case Some(lock) => lock.acquire(taskId, holderAddress, lockTtl)
        case None       => Future.successful(true)
      }

      context.pipeToSelf(lockFut) {
        case Success(b)  => LockAcquired(b)
        case Failure(ex) => LockAcquireFailed(ex)
      }
      Behaviors.same

    case LockAcquired(true) =>
      context.log.info(s"[$taskId] Lock acquired, starting CDC engine")
      implicit val ec: ExecutionContext = context.executionContext

      val engineFut: Future[CDCEngine] =
        Future(engineFactory()).flatMap { eng => eng.start().map(_ => eng) }

      context.pipeToSelf(engineFut) {
        case Success(e)  => EngineStarted(e)
        case Failure(ex) => EngineStartFailed(ex)
      }
      Behaviors.same

    case LockAcquired(false) if retriesRemaining > 0 =>
      context.log.warn(
        s"[$taskId] Lock held by another holder; backing off ${retryBackoff.toSeconds}s, " +
          s"$retriesRemaining retries left"
      )
      // 调度单次定时器，到期后重发 StartEngine
      timers.startSingleTimer("lock-acquire-retry", StartEngine, retryBackoff)
      initializing(
        taskId, engineFactory, lockOpt, lockTtl, heartbeatInterval,
        holderAddress, timers, context,
        retriesRemaining = retriesRemaining - 1,
        retryBackoff     = retryBackoff
      )

    case LockAcquired(false) =>
      context.log.error(
        s"[$taskId] Lock acquire exhausted retries, giving up. ClusterSingleton will retry spawn."
      )
      Behaviors.stopped

    case LockAcquireFailed(ex) =>
      context.log.error(s"[$taskId] Lock acquire failed (DB error): ${ex.getMessage}", ex)
      Behaviors.stopped

    case EngineStarted(engine) =>
      context.log.info(s"[$taskId] CDC engine started, entering Running state")
      // 启动 heartbeat 定时器（即使 lock=None 也启动，便于运维观察心跳）
      timers.startTimerWithFixedDelay(Heartbeat, heartbeatInterval)
      running(taskId, engine, lockOpt, lockTtl, heartbeatInterval, holderAddress, timers, context)

    case EngineStartFailed(ex) =>
      context.log.error(s"[$taskId] CDC engine start failed: ${ex.getMessage}", ex)
      // 释放锁后退出 Behavior，让 ClusterSingleton 决定是否重启
      lockOpt.foreach { lock =>
        lock.release(taskId, holderAddress)(context.executionContext)
      }
      Behaviors.stopped

    case GetStatus(replyTo) =>
      replyTo ! StatusReply.Success(TaskStatus(taskId, "Initializing", holderAddress, None))
      Behaviors.same

    case Shutdown(replyTo) =>
      context.log.info(s"[$taskId] Shutdown requested during initialization")
      lockOpt.foreach { lock =>
        lock.release(taskId, holderAddress)(context.executionContext)
      }
      replyTo ! StatusReply.Success(Done)
      Behaviors.stopped

    case other =>
      context.log.debug(s"[$taskId] Initializing: ignoring $other")
      Behaviors.unhandled
  }

  // ========== 状态：Running ==========

  private def running(
    taskId: String,
    engine: CDCEngine,
    lockOpt: Option[SingletonLock],
    lockTtl: FiniteDuration,
    heartbeatInterval: FiniteDuration,
    holderAddress: String,
    timers: TimerScheduler[Command],
    context: ActorContext[Command]
  ): Behavior[Command] = Behaviors
    .receiveMessage[Command] {

      case Heartbeat =>
        implicit val ec: ExecutionContext = context.executionContext
        val renewFut = lockOpt match {
          case Some(lock) => lock.renew(taskId, holderAddress, lockTtl)
          case None       => Future.successful(true)
        }
        context.pipeToSelf(renewFut) {
          case Success(b) => HeartbeatResult(b)
          case Failure(_) => HeartbeatResult(false)
        }
        Behaviors.same

      case HeartbeatResult(true) =>
        context.log.debug(s"[$taskId] Heartbeat OK")
        Behaviors.same

      case HeartbeatResult(false) =>
        context.log.warn(s"[$taskId] Heartbeat lost lock — stopping engine and exiting Behavior")
        timers.cancel(Heartbeat)
        // 紧急路径：lock 丢失意味着可能有另一持有者，必须停 engine
        engine.stop()
        Behaviors.stopped

      case GetStatus(replyTo) =>
        replyTo ! StatusReply.Success(
          TaskStatus(taskId, "Running", holderAddress, Some(engine.getCurrentState().name))
        )
        Behaviors.same

      case Shutdown(replyTo) =>
        context.log.info(s"[$taskId] Shutdown requested in Running state")
        timers.cancel(Heartbeat)
        implicit val ec: ExecutionContext = context.executionContext

        val stopFut = engine.stop().flatMap { _ =>
          lockOpt match {
            case Some(lock) => lock.release(taskId, holderAddress).map(_ => Done)
            case None       => Future.successful(Done)
          }
        }

        context.pipeToSelf(stopFut) {
          case Success(_)  => EngineStopped(replyTo)
          case Failure(ex) => EngineStopFailed(ex, replyTo)
        }
        stopping(taskId, holderAddress, context)

      case other =>
        context.log.debug(s"[$taskId] Running: ignoring $other")
        Behaviors.unhandled
    }
    .receiveSignal { case (_, PostStop) =>
      // ClusterSingleton 迁移或节点关闭时会触发 PostStop —— fire-and-forget 停 engine
      context.log.info(s"[$taskId] PostStop received, stopping engine and releasing lock (best-effort)")
      try {
        engine.stop()
        lockOpt.foreach { lock =>
          lock.release(taskId, holderAddress)(context.executionContext)
        }
      } catch {
        case ex: Throwable =>
          context.log.error(s"[$taskId] PostStop cleanup error: ${ex.getMessage}", ex)
      }
      Behaviors.same
    }

  // ========== 状态：Stopping ==========

  private def stopping(
    taskId: String,
    holderAddress: String,
    context: ActorContext[Command]
  ): Behavior[Command] = Behaviors.receiveMessage {

    case EngineStopped(replyTo) =>
      context.log.info(s"[$taskId] Engine stopped successfully")
      replyTo ! StatusReply.Success(Done)
      Behaviors.stopped

    case EngineStopFailed(ex, replyTo) =>
      context.log.error(s"[$taskId] Engine stop failed: ${ex.getMessage}", ex)
      replyTo ! StatusReply.Error(ex)
      Behaviors.stopped

    case GetStatus(replyTo) =>
      replyTo ! StatusReply.Success(TaskStatus(taskId, "Stopping", holderAddress, None))
      Behaviors.same

    case _ =>
      Behaviors.unhandled
  }
}
