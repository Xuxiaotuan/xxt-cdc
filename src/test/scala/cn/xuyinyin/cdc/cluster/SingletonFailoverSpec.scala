package cn.xuyinyin.cdc.cluster

import cn.xuyinyin.cdc.CborSerializable
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.apache.pekko.util.Timeout
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Span, Seconds, Millis}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * 单 JVM 多 ActorSystem 验证 ClusterSingleton failover：
 *
 * 拓扑（同 JVM 多端口）：
 *   - masterA (seed/oldest): master role，启动 Singleton
 *   - masterB:               master role，candidate 接管者
 *   - worker:                worker role，发起 ask 验证
 *
 * 流程：
 *   1. 3 个 ActorSystem 形成 cluster
 *   2. 每节点 init Singleton（hostingRole=master）
 *   3. worker ask Singleton → 拿到 hostAddress（应在 masterA）
 *   4. masterA.terminate()
 *   5. worker 轮询 ask Singleton → hostAddress 应切到 masterB
 *
 * 用真 Pekko API 直接测，避免 multi-node-testkit conductor 协议带来的同步复杂度。
 */
object SingletonFailoverSpec {
  // 跨节点消息（同 JVM 不同 ActorSystem 通信也走 remote 序列化）
  final case class EchoCmd(msg: String, replyTo: ActorRef[EchoResp]) extends CborSerializable
  final case class EchoResp(msg: String, hostAddress: String)        extends CborSerializable

  val echoBehavior: Behavior[EchoCmd] = Behaviors.setup { ctx =>
    val addr = ctx.system.address.toString
    ctx.log.info(s"[failover-test] singleton spawned at $addr")
    Behaviors.receiveMessage { case EchoCmd(msg, rt) =>
      rt ! EchoResp(msg, addr)
      Behaviors.same
    }
  }

  def cfg(port: Int, role: String): Config =
    ConfigFactory.parseString(s"""
      |pekko.actor.provider = "cluster"
      |pekko.remote.artery.canonical.hostname = "127.0.0.1"
      |pekko.remote.artery.canonical.port = $port
      |pekko.cluster.roles = ["$role"]
      |pekko.cluster.failure-detector.acceptable-heartbeat-pause = 3s
      |pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
      |pekko.cluster.split-brain-resolver.active-strategy = "keep-majority"
      |pekko.cluster.split-brain-resolver.stable-after = 5s
      |pekko.actor.serialization-bindings {
      |  "cn.xuyinyin.cdc.CborSerializable" = jackson-cbor
      |}
      |pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      |pekko.coordinated-shutdown.exit-jvm = off
      |""".stripMargin)
}

class SingletonFailoverSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with Eventually {

  import SingletonFailoverSpec._

  private val portA      = 27551
  private val portB      = 27552
  private val portWorker = 27553

  private val systemA: ActorSystem[Any] =
    ActorSystem(Behaviors.empty[Any], "FailoverCluster", cfg(portA, "master"))
  private val systemB: ActorSystem[Any] =
    ActorSystem(Behaviors.empty[Any], "FailoverCluster", cfg(portB, "master"))
  private val systemWorker: ActorSystem[Any] =
    ActorSystem(Behaviors.empty[Any], "FailoverCluster", cfg(portWorker, "worker"))

  override def afterAll(): Unit = {
    // 注意：testkit 自带的 system 不在我们这 3 个之列，单独 terminate
    Seq(systemA, systemB, systemWorker).foreach { s =>
      try s.terminate() catch { case _: Throwable => () }
    }
    super.afterAll()
  }

  private implicit val askTimeout: Timeout = Timeout(8.seconds)

  /** 在指定 system 上 ask 一次 Singleton ref，返回 hostAddress（带轮询）。 */
  private def askHost(ref: ActorRef[EchoCmd], label: String, system: ActorSystem[Any]): String = {
    implicit val sched = system.scheduler
    var lastErr: Throwable = null
    var attempts          = 0
    val maxAttempts       = 20
    while (attempts < maxAttempts) {
      try {
        val resp = Await.result(
          ref.ask((rt: ActorRef[EchoResp]) => EchoCmd(label, rt)),
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
    throw new AssertionError(s"askSingleton on ${system.name} failed after $maxAttempts attempts: ${Option(lastErr).map(_.getMessage).orNull}")
  }

  "ClusterSingleton (single-JVM, 3 ActorSystems)" should {

    "failover from masterA to masterB after masterA terminates" in {
      // 1) 3 节点 join 同一 cluster（masterA = seed）
      val seedAddr = Cluster(systemA).selfMember.uniqueAddress.address
      Cluster(systemA).manager ! Join(seedAddr)
      Cluster(systemB).manager ! Join(seedAddr)
      Cluster(systemWorker).manager ! Join(seedAddr)

      // 等到所有节点都看到 3 个 Up 成员
      eventually(timeout(Span(30, Seconds)), interval(Span(500, Millis))) {
        Seq(systemA, systemB, systemWorker).foreach { s =>
          val members = Cluster(s).state.members
          assert(members.size == 3, s"${s.name} sees ${members.size} members: ${members.map(_.address).mkString(",")}")
        }
      }

      // 2) 每节点 init Singleton（hostingRole=master，worker 拿 proxy）
      val refA = TaskSingletonManager.init(systemA, "ft-task", echoBehavior, "master")
      val refB = TaskSingletonManager.init(systemB, "ft-task", echoBehavior, "master")
      val refW = TaskSingletonManager.init(systemWorker, "ft-task", echoBehavior, "master")

      // 3) worker 拿到 pre-failover host（应在 masterA）
      val preHost = askHost(refW, "pre-failover", systemWorker)
      info(s"[worker] Pre-failover host: $preHost")
      preHost should include(s"$portA")

      // 4) masterA terminate（模拟节点退出）
      info("[test] terminating masterA")
      systemA.terminate()
      Await.ready(systemA.whenTerminated, 10.seconds)

      // 5) worker 轮询验证 Singleton 切到 masterB
      eventually(timeout(Span(45, Seconds)), interval(Span(1, Seconds))) {
        val postHost = askHost(refW, "post-failover", systemWorker)
        info(s"[worker] Post-failover host: $postHost")
        postHost should not be preHost
        postHost should include(s"$portB")
      }
    }
  }
}
