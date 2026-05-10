package cn.xuyinyin.cdc.cluster

import org.apache.pekko.actor.{Actor, ActorLogging, Address, Props}
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent._
import org.apache.pekko.cluster.Member

/**
 * Cluster 状态探针（classic actor）
 *
 * 订阅 Pekko Cluster 成员事件，在内存维护当前可见的成员快照，
 * 提供 [[ClusterStateProbe.GetMembers]] 查询接口供 REST API 使用。
 *
 * Phase 1 仅做"可观察"，不参与决策；Phase 2 起 ClusterSingleton 会复用它。
 */
class ClusterStateProbe extends Actor with ActorLogging {

  import ClusterStateProbe._

  private val cluster = Cluster(context.system)

  // address -> MemberInfo
  private var members: Map[Address, MemberInfo] = Map.empty

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[ReachabilityEvent]
    )
    log.info("ClusterStateProbe subscribed to cluster events")
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  override def receive: Receive = {
    case MemberUp(m) =>
      log.info(s"[Cluster] MemberUp: ${m.address} roles=${m.roles}")
      members = members.updated(m.address, toInfo(m, reachable = true))

    case MemberJoined(m) =>
      log.info(s"[Cluster] MemberJoined: ${m.address}")
      members = members.updated(m.address, toInfo(m, reachable = true))

    case MemberWeaklyUp(m) =>
      log.info(s"[Cluster] MemberWeaklyUp: ${m.address}")
      members = members.updated(m.address, toInfo(m, reachable = true))

    case MemberLeft(m) =>
      log.info(s"[Cluster] MemberLeft: ${m.address}")
      members = members.updated(m.address, toInfo(m, reachable = true))

    case MemberExited(m) =>
      log.info(s"[Cluster] MemberExited: ${m.address}")
      members = members.updated(m.address, toInfo(m, reachable = true))

    case MemberRemoved(m, _) =>
      log.info(s"[Cluster] MemberRemoved: ${m.address}")
      members = members - m.address

    case UnreachableMember(m) =>
      log.warning(s"[Cluster] UnreachableMember: ${m.address}")
      members.get(m.address).foreach { info =>
        members = members.updated(m.address, info.copy(reachable = false))
      }

    case ReachableMember(m) =>
      log.info(s"[Cluster] ReachableMember: ${m.address}")
      members.get(m.address).foreach { info =>
        members = members.updated(m.address, info.copy(reachable = true))
      }

    case _: MemberEvent => // 其他子事件忽略

    case GetMembers =>
      sender() ! MembersResponse(
        selfAddress = cluster.selfAddress,
        selfRoles = cluster.selfRoles,
        members = members.values.toSeq.sortBy(_.address.toString)
      )
  }

  private def toInfo(m: Member, reachable: Boolean): MemberInfo =
    MemberInfo(
      address = m.address,
      status = m.status.toString,
      roles = m.roles,
      reachable = reachable
    )
}

object ClusterStateProbe {

  def props(): Props = Props(new ClusterStateProbe)

  /** 查询当前 cluster 成员（同步 ask 模式） */
  case object GetMembers

  /** 单个成员的快照信息 */
  final case class MemberInfo(
    address: Address,
    status: String,
    roles: Set[String],
    reachable: Boolean
  )

  /** GetMembers 的响应 */
  final case class MembersResponse(
    selfAddress: Address,
    selfRoles: Set[String],
    members: Seq[MemberInfo]
  )

  /** REST 端点用的简单类型别名（避免直接暴露 Pekko Address 类型给 JSON 层） */
  final case class MemberJson(
    address: String,
    status: String,
    roles: Seq[String],
    reachable: Boolean
  )

  object MemberJson {
    def fromInfo(info: MemberInfo): MemberJson =
      MemberJson(
        address = info.address.toString,
        status = info.status,
        roles = info.roles.toSeq.sorted,
        reachable = info.reachable
      )
  }
}
