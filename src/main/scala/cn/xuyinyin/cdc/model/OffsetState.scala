package cn.xuyinyin.cdc.model

/**
 * 偏移量状态
 * 状态转换：RECEIVED → APPLIED → COMMITTED
 */
sealed trait OffsetState {
  def name: String
}

case object Received extends OffsetState {
  override def name: String = "RECEIVED"
}

case object Applied extends OffsetState {
  override def name: String = "APPLIED"
}

case object Committed extends OffsetState {
  override def name: String = "COMMITTED"
}

object OffsetState {
  /**
   * 验证偏移量状态转换是否合法
   * 只允许 RECEIVED → APPLIED → COMMITTED
   */
  def isValidTransition(from: OffsetState, to: OffsetState): Boolean = {
    (from, to) match {
      case (Received, Applied) => true
      case (Applied, Committed) => true
      case _ => false
    }
  }
  
  /**
   * 获取下一个合法状态
   */
  def nextState(current: OffsetState): Option[OffsetState] = current match {
    case Received => Some(Applied)
    case Applied => Some(Committed)
    case Committed => None
  }
}
