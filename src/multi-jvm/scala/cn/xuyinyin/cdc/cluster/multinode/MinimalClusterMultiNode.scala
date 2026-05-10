package cn.xuyinyin.cdc.cluster.multinode

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.Address
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import org.apache.pekko.remote.testconductor.RoleName
import org.apache.pekko.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import org.apache.pekko.testkit.ImplicitSender
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

/**
 * Phase 2.3 基础设施验证：3 个真 JVM 形成 Pekko Cluster。
 *
 * 入口类 `*MultiJvmNodeN` 由 sbt-multi-jvm 在 N 个 JVM 中分别启动；
 * 各 JVM 通过 [[MultiNodeConfig.role]] 自我识别自己是哪个 role。
 *
 * 跑命令：`sbt multi-jvm:test`
 */
object MinimalClusterMultiNodeConfig extends MultiNodeConfig {
  val node1: RoleName = role("node1")
  val node2: RoleName = role("node2")
  val node3: RoleName = role("node3")

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
}

abstract class MinimalClusterMultiNodeSpec
    extends MultiNodeSpec(MinimalClusterMultiNodeConfig)
    with AnyWordSpecLike
    with Matchers
    with ImplicitSender {

  import MinimalClusterMultiNodeConfig._

  override def initialParticipants: Int = roles.size

  "Pekko Cluster (multi-jvm)" must {

    "form a 3-node cluster" in {
      Cluster(system).subscribe(testActor, InitialStateAsEvents, classOf[MemberUp])

      // 所有节点都 join node1（让 node1 成为 cluster 的初始 seed）
      Cluster(system).join(node(node1).address)

      val seen = scala.collection.mutable.Set[Address]()
      within(20.seconds) {
        while (seen.size < roles.size) {
          expectMsgPF() { case MemberUp(member) => seen += member.address }
        }
      }
      seen.size shouldBe roles.size

      enterBarrier("cluster-formed")
    }
  }
}

// 每个节点的 JVM 入口（sbt-multi-jvm 用 *MultiJvmNodeN 后缀识别）
class MinimalClusterMultiNodeSpecMultiJvmNode1 extends MinimalClusterMultiNodeSpec
class MinimalClusterMultiNodeSpecMultiJvmNode2 extends MinimalClusterMultiNodeSpec
class MinimalClusterMultiNodeSpecMultiJvmNode3 extends MinimalClusterMultiNodeSpec
