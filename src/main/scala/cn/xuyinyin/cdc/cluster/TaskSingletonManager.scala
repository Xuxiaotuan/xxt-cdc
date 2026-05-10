package cn.xuyinyin.cdc.cluster

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.cluster.typed.{ClusterSingleton, ClusterSingletonSettings, SingletonActor}

import scala.concurrent.duration._

/**
 * 把任意 typed Behavior 包装为 ClusterSingleton。
 *
 * 设计要点：
 *   - `hostingRole` 限定哪些角色的节点可以托管 Singleton。
 *     例如 `hostingRole = "master"` 时，只有打了 master role 的节点才会真正
 *     运行 Behavior；worker 节点上调用同样的 init 只会得到一个透明的代理 ActorRef。
 *   - 调用方拿到的 ActorRef 是位置透明的：发命令时 Pekko 自动路由到 Singleton 实例。
 *
 * Phase 2.1：仅暴露最简形式（taskId 一一对应 Singleton 名）。
 * Phase 3 多任务调度时 TaskRegistry 会复用此工厂。
 */
object TaskSingletonManager {

  /**
   * 在 cluster 中初始化（或获取）一个 Task Singleton。
   *
   * @param system      typed ActorSystem
   * @param taskId      Singleton 名（同 cluster 内必须唯一）
   * @param behavior    Singleton 内部 Behavior
   * @param hostingRole 允许托管 Singleton 的角色（默认 "master"）
   * @param handOverRetryInterval Singleton 切换时的重试间隔
   */
  def init[T](
    system: ActorSystem[_],
    taskId: String,
    behavior: Behavior[T],
    hostingRole: String = "master",
    handOverRetryInterval: FiniteDuration = 1.second
  ): ActorRef[T] = {
    val singleton = ClusterSingleton(system)
    val settings  = ClusterSingletonSettings(system).withRole(hostingRole)
    singleton.init(SingletonActor(behavior, taskId).withSettings(settings))
  }
}
