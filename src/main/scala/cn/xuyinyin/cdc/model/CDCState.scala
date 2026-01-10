package cn.xuyinyin.cdc.model

/**
 * CDC 引擎生命周期状态
 */
sealed trait CDCState {
  def name: String
}

case object Init extends CDCState {
  override def name: String = "INIT"
}

case object Snapshot extends CDCState {
  override def name: String = "SNAPSHOT"
}

case object Catchup extends CDCState {
  override def name: String = "CATCHUP"
}

case object Streaming extends CDCState {
  override def name: String = "STREAMING"
}

object CDCState {
  /**
   * 验证状态转换是否合法
   * 只允许 INIT → Streaming
   * 只允许 INIT → SNAPSHOT → CATCHUP → STREAMING
   */
  def isValidTransition(from: CDCState, to: CDCState): Boolean = {
    (from, to) match {
      case (Init, Streaming) => true
      case (Init, Snapshot) => true
      case (Snapshot, Catchup) => true
      case (Catchup, Streaming) => true
      case _ => false
    }
  }
  
  /**
   * 获取下一个合法状态
   */
  def nextState(current: CDCState): Option[CDCState] = current match {
    case Init => Some(Snapshot)
    case Snapshot => Some(Catchup)
    case Catchup => Some(Streaming)
    case Streaming => None
  }
}
