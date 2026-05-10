package cn.xuyinyin.cdc.cluster

import cn.xuyinyin.cdc.CborSerializable
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.ServerSocket
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/**
 * 测试 [[TaskSingletonManager]]：3 节点 cluster（2 master + 1 worker），
 * 验证 hostingRole=master 限定 Singleton 仅在 master 节点上 spawn，
 * 而 worker 节点拿到的是透明 proxy。
 *
 * 由于未引入 sbt-multi-jvm，仍用单 JVM 多 ActorSystem 模拟。
 * Phase 2.3 引入插件后会用 multi-node-testkit 跑真多 JVM + kill-9 failover。
 */
object TaskSingletonManagerSpec {
  // 测试用 echo Behavior：返回收到的消息 + 自己所在节点的 address
  // 必须是顶层类（不能是 spec 内的 inner class），否则 Jackson 反序列化时找不到 instantiator
  final case class EchoCmd(msg: String, replyTo: ActorRef[EchoResp]) extends CborSerializable
  final case class EchoResp(msg: String, hostAddress: String)        extends CborSerializable
}

class TaskSingletonManagerSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll with Eventually {

  import TaskSingletonManagerSpec._

  private implicit val askTimeout: Timeout = Timeout(8.seconds)

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(45, Seconds), interval = Span(500, Millis))

  private val SystemName = "cdc-system"

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }

  private def buildConfig(port: Int, role: String, seedNodes: Seq[String]) = {
    val seedHocon = seedNodes
      .map(s => s""""pekko://$SystemName@$s"""")
      .mkString("[", ",", "]")
    ConfigFactory
      .parseString(
        s"""
           |pekko.actor.provider = "cluster"
           |pekko.actor.serialization-bindings {
           |  "cn.xuyinyin.cdc.CborSerializable" = jackson-cbor
           |}
           |pekko.remote.artery.transport = tcp
           |pekko.remote.artery.canonical.hostname = "127.0.0.1"
           |pekko.remote.artery.canonical.port = $port
           |pekko.cluster.roles = ["$role"]
           |pekko.cluster.seed-nodes = $seedHocon
           |pekko.cluster.failure-detector.acceptable-heartbeat-pause = 3s
           |pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
           |pekko.cluster.split-brain-resolver.active-strategy = "keep-majority"
           |pekko.cluster.split-brain-resolver.stable-after = 5s
           |pekko.loglevel = "WARN"
           |pekko.coordinated-shutdown.exit-jvm = off
           |pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
      .resolve()
  }

  private val echoBehavior: Behavior[EchoCmd] = Behaviors.setup { context =>
    val addr = context.system.address.toString
    context.log.info(s"Test echo singleton spawned at $addr")
    Behaviors.receiveMessage { case EchoCmd(msg, replyTo) =>
      replyTo ! EchoResp(msg, addr)
      Behaviors.same
    }
  }

  private var portM1: Int            = _
  private var portM2: Int            = _
  private var portW: Int             = _
  private var systemM1: ActorSystem  = _
  private var systemM2: ActorSystem  = _
  private var systemW: ActorSystem   = _

  override def beforeAll(): Unit = {
    portM1 = freePort()
    portM2 = freePort()
    portW  = freePort()
    val seed = s"127.0.0.1:$portM1"

    systemM1 = ActorSystem(SystemName, buildConfig(portM1, "master", Seq(seed)))
    systemM2 = ActorSystem(SystemName, buildConfig(portM2, "master", Seq(seed)))
    systemW  = ActorSystem(SystemName, buildConfig(portW,  "worker", Seq(seed)))
  }

  override def afterAll(): Unit = {
    if (systemW != null)  Await.ready(systemW.terminate(),  20.seconds)
    if (systemM2 != null) Await.ready(systemM2.terminate(), 20.seconds)
    if (systemM1 != null) Await.ready(systemM1.terminate(), 20.seconds)
  }

  "TaskSingletonManager" should {

    "host singleton only on master-roled nodes (worker proxies through)" in {
      val taskId = "test-singleton-task"

      // 三个节点都 init —— master 节点上才会 spawn behavior，worker 拿到的是 proxy
      val refM1 = TaskSingletonManager.init(systemM1.toTyped, taskId, echoBehavior, hostingRole = "master")
      val refM2 = TaskSingletonManager.init(systemM2.toTyped, taskId, echoBehavior, hostingRole = "master")
      val refW  = TaskSingletonManager.init(systemW.toTyped,  taskId, echoBehavior, hostingRole = "master")

      val masterAddrs = Set(s"pekko://$SystemName@127.0.0.1:$portM1", s"pekko://$SystemName@127.0.0.1:$portM2")
      val workerAddr  = s"pekko://$SystemName@127.0.0.1:$portW"

      // 等 cluster 稳定 + Singleton spawn —— 通过反复 ask 直到收到响应
      eventually {
        implicit val sched = systemW.toTyped.scheduler
        val resp: Future[EchoResp] = refW.ask(replyTo => EchoCmd("hello-from-worker", replyTo))
        val r = Await.result(resp, askTimeout.duration)

        // 关键断言：响应来自 master 节点，绝不能是 worker 节点
        masterAddrs should contain(r.hostAddress)
        r.hostAddress should not be workerAddr
        r.msg shouldBe "hello-from-worker"
      }

      // 同一时刻从两个 master 节点也发，应该路由到**同一个** Singleton 实例
      eventually {
        val r1 = {
          implicit val sched = systemM1.toTyped.scheduler
          Await.result(refM1.ask((rt: ActorRef[EchoResp]) => EchoCmd("from-m1", rt)), askTimeout.duration)
        }
        val r2 = {
          implicit val sched = systemM2.toTyped.scheduler
          Await.result(refM2.ask((rt: ActorRef[EchoResp]) => EchoCmd("from-m2", rt)), askTimeout.duration)
        }

        // 同 cluster 内 Singleton 唯一，两次响应来自同一 host
        r1.hostAddress shouldBe r2.hostAddress
        masterAddrs should contain(r1.hostAddress)
      }
    }
  }
}
