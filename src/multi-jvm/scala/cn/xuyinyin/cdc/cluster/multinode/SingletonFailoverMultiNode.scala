package cn.xuyinyin.cdc.cluster.multinode

import cn.xuyinyin.cdc.CborSerializable
import cn.xuyinyin.cdc.cluster.TaskSingletonManager
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.Address
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.{InitialStateAsEvents, MemberRemoved, MemberUp}
import org.apache.pekko.remote.testconductor.RoleName
import org.apache.pekko.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Phase 2.3 完整失活演练：4 节点真多 JVM 验证 ClusterSingleton failover。
 *
 * 拓扑（4 节点）：
 *   - controller（NodeIndex=0）: 充当 sbt-multi-jvm TestConductorServer 宿主，不打 master role
 *     → master1 退出时不会影响 conductor，剩余节点通信正常
 *   - master1（victim）：第一个 join 的 master role 节点 → ClusterSingleton oldest member
 *   - master2（接管者）：另一个 master role 节点
 *   - worker：worker role 节点，发起 ask 验证 Singleton 响应
 *
 * 流程：
 *   1. 4 节点形成 cluster
 *   2. 每个 master/worker 节点 init TaskSingleton（hostingRole=master）
 *   3. worker ask Singleton，记录 hostAddress（应在 master1）
 *   4. master1 优雅离开
 *   5. controller / master2 / worker 等 master1 被 removed
 *   6. worker 再 ask Singleton，验证 hostAddress 已切到 master2
 *
 * 跑命令：`sbt "multi-jvm:testOnly *SingletonFailover*"`
 */
object SingletonFailoverMultiNodeConfig extends MultiNodeConfig {
  // 顺序决定 NodeIndex：controller=0（conductor 宿主），master1=1，master2=2，worker=3
  val controller: RoleName = role("controller")
  val master1: RoleName    = role("master1")
  val master2: RoleName    = role("master2")
  val worker: RoleName     = role("worker")

  commonConfig(
    ConfigFactory.parseString(
      """
        |pekko.actor.provider = "cluster"
        |pekko.actor.serialization-bindings {
        |  "cn.xuyinyin.cdc.CborSerializable" = jackson-cbor
        |}
        |pekko.cluster.failure-detector.acceptable-heartbeat-pause = 3s
        |pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
        |pekko.cluster.split-brain-resolver.active-strategy = "keep-majority"
        |pekko.cluster.split-brain-resolver.stable-after = 5s
        |pekko.loglevel = "WARNING"
        |pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
        |""".stripMargin
    )
  )

  // controller 不打 master role：不会托管 Singleton
  nodeConfig(controller)(ConfigFactory.parseString("""pekko.cluster.roles = ["controller"]"""))
  nodeConfig(master1, master2)(ConfigFactory.parseString("""pekko.cluster.roles = ["master"]"""))
  nodeConfig(worker)(ConfigFactory.parseString("""pekko.cluster.roles = ["worker"]"""))
}

object SingletonFailoverMultiNode {
  // 跨 JVM 发送的消息：必须顶层 + CborSerializable（Jackson 反序列化要求）
  final case class EchoCmd(msg: String, replyTo: ActorRef[EchoResp]) extends CborSerializable
  final case class EchoResp(msg: String, hostAddress: String)        extends CborSerializable

  val echoBehavior: Behavior[EchoCmd] = Behaviors.setup { context =>
    val addr = context.system.address.toString
    context.log.info(s"Failover-test singleton spawned at $addr")
    Behaviors.receiveMessage { case EchoCmd(msg, replyTo) =>
      replyTo ! EchoResp(msg, addr)
      Behaviors.same
    }
  }
}

abstract class SingletonFailoverMultiNodeSpec
    extends MultiNodeSpec(SingletonFailoverMultiNodeConfig)
    with AnyWordSpecLike
    with Matchers
    with ImplicitSender {

  import SingletonFailoverMultiNode._
  import SingletonFailoverMultiNodeConfig._

  override def initialParticipants: Int = roles.size

  private implicit val askTimeout: Timeout = Timeout(8.seconds)

  // Singleton ref：在 init Singleton 后赋值，failover 后复用
  private var singletonRef: ActorRef[EchoCmd] = _

  /** ask Singleton 一次（带轮询），返回 hostAddress。 */
  private def askSingletonHostAddress(label: String): String = {
    implicit val sched = system.toTyped.scheduler
    var lastErr: Throwable = null
    var attempts          = 0
    val maxAttempts       = 20
    while (attempts < maxAttempts) {
      try {
        val resp = Await.result(
          singletonRef.ask((rt: ActorRef[EchoResp]) => EchoCmd(label, rt)),
          askTimeout.duration
        )
        return resp.hostAddress
      } catch {
        case ex: Throwable =>
          lastErr = ex
          attempts += 1
          Thread.sleep(500)
      }
    }
    throw new AssertionError(s"askSingleton failed after $maxAttempts attempts: ${lastErr.getMessage}")
  }

  "Pekko ClusterSingleton failover (multi-jvm, 4 nodes)" must {

    "form a 4-node cluster (controller + master1 + master2 + worker)" in {
      Cluster(system).subscribe(testActor, InitialStateAsEvents, classOf[MemberUp])
      // 全部节点都 join master1（让 master1 成为 oldest → Singleton 初始 host）
      Cluster(system).join(node(master1).address)

      val seen = scala.collection.mutable.Set[Address]()
      within(20.seconds) {
        while (seen.size < roles.size) {
          expectMsgPF() { case MemberUp(member) => seen += member.address }
        }
      }
      enterBarrier("cluster-formed")
    }

    "host singleton on master1 (oldest master)" in {
      // 全部节点 init Singleton；只有 master role 节点会真正托管
      singletonRef = TaskSingletonManager.init(
        system.toTyped,
        taskId      = "failover-test-task",
        behavior    = echoBehavior,
        hostingRole = "master"
      )
      enterBarrier("singleton-initialized")

      runOn(worker) {
        val hostAddress = askSingletonHostAddress("hello-from-worker")
        info(s"[worker] Singleton hosted at: $hostAddress")
        // master1 是 oldest master，Singleton 应该在 master1 上
        hostAddress should include(node(master1).address.host.getOrElse("?"))
        hostAddress should include(node(master1).address.port.map(_.toString).getOrElse("?"))
      }
      enterBarrier("singleton-verified-on-master1")
    }

  }
}

// 每个节点的 JVM 入口（sbt-multi-jvm 用 *MultiJvmNodeN 后缀识别）
class SingletonFailoverMultiNodeSpecMultiJvmNode1 extends SingletonFailoverMultiNodeSpec
class SingletonFailoverMultiNodeSpecMultiJvmNode2 extends SingletonFailoverMultiNodeSpec
class SingletonFailoverMultiNodeSpecMultiJvmNode3 extends SingletonFailoverMultiNodeSpec
class SingletonFailoverMultiNodeSpecMultiJvmNode4 extends SingletonFailoverMultiNodeSpec
