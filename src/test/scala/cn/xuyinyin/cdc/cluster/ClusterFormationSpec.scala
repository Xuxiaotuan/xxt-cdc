package cn.xuyinyin.cdc.cluster

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.ServerSocket
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Phase 1: Cluster 形成测试（单 JVM 多 ActorSystem）
 *
 * 由于 project/plugins.sbt 未引入 sbt-multi-jvm，这里用单 JVM 内多 ActorSystem
 * 模拟双节点 cluster 形成；真正的多 JVM / 网络分裂测试留给 Phase 2 引入
 * sbt-multi-jvm 后再用 multi-node-testkit 补充。
 */
class ClusterFormationSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll with Eventually {

  private implicit val askTimeout: Timeout = Timeout(5.seconds)

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(500, Millis))

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
    // buildConfig 显式覆盖在最高优先级（左侧），fallback 到 application.conf + reference.conf；
    // application.conf 在测试 classpath 中将 provider override 回 local，但这里 buildConfig 重新强制 cluster。
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
           |pekko.cluster.split-brain-resolver.stable-after = 7s
           |pekko.loglevel = "WARN"
           |pekko.coordinated-shutdown.exit-jvm = off
           |pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
           |""".stripMargin
      )
      .withFallback(ConfigFactory.load())
      .resolve()
  }

  private var port1: Int                = _
  private var port2: Int                = _
  private var system1: ActorSystem      = _
  private var system2: ActorSystem      = _

  override def beforeAll(): Unit = {
    port1 = freePort()
    port2 = freePort()
    val seed1 = s"127.0.0.1:$port1"
    // master 自己作为 seed；worker 也从 seed1 开始 join
    system1 = ActorSystem(SystemName, buildConfig(port1, "master", Seq(seed1)))
    system2 = ActorSystem(SystemName, buildConfig(port2, "worker", Seq(seed1)))
  }

  override def afterAll(): Unit = {
    if (system2 != null) Await.ready(system2.terminate(), 15.seconds)
    if (system1 != null) Await.ready(system1.terminate(), 15.seconds)
  }

  "Pekko Cluster" should {

    "form a 2-node cluster with master + worker roles" in {
      val probe1 = system1.actorOf(ClusterStateProbe.props(), "probe-master")
      val probe2 = system2.actorOf(ClusterStateProbe.props(), "probe-worker")

      eventually {
        val resp1 = Await
          .result(
            (probe1 ? ClusterStateProbe.GetMembers).mapTo[ClusterStateProbe.MembersResponse],
            askTimeout.duration
          )
        val resp2 = Await
          .result(
            (probe2 ? ClusterStateProbe.GetMembers).mapTo[ClusterStateProbe.MembersResponse],
            askTimeout.duration
          )

        // 双方都看到 2 个成员
        resp1.members.size shouldBe 2
        resp2.members.size shouldBe 2

        // 双方都看到 master + worker 两个角色
        val allRoles1 = resp1.members.flatMap(_.roles).toSet
        allRoles1 should contain allOf ("master", "worker")
        val allRoles2 = resp2.members.flatMap(_.roles).toSet
        allRoles2 should contain allOf ("master", "worker")

        // 双方都视所有成员为 reachable
        resp1.members.forall(_.reachable) shouldBe true
        resp2.members.forall(_.reachable) shouldBe true

        // 双方各自的 selfRoles 与启动时一致
        resp1.selfRoles should contain("master")
        resp2.selfRoles should contain("worker")
      }
    }
  }
}
