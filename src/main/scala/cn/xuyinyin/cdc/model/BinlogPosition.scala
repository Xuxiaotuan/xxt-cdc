package cn.xuyinyin.cdc.model

/**
 * Binlog 位置抽象
 * 支持 GTID 和 File+Position 两种格式
 */
sealed trait BinlogPosition {
  def asString: String
  
  /**
   * 比较两个 binlog 位置
   * @return 负数表示 this < that，0 表示相等，正数表示 this > that
   */
  def compare(that: BinlogPosition): Int = {
    (this, that) match {
      case (FilePosition(f1, p1), FilePosition(f2, p2)) =>
        val fileCompare = f1.compareTo(f2)
        if (fileCompare != 0) fileCompare else p1.compareTo(p2)
      case (GTIDPosition(g1), GTIDPosition(g2)) =>
        g1.compareTo(g2) // 简化比较，实际应该解析 GTID 集合
      case _ =>
        // 不同类型的位置无法比较，返回 0
        0
    }
  }
}

/**
 * GTID 位置格式
 * 
 * @param gtidSet GTID 集合字符串
 */
case class GTIDPosition(gtidSet: String) extends BinlogPosition {
  override def asString: String = s"GTID:$gtidSet"
}

/**
 * File+Position 位置格式
 * 
 * @param filename binlog 文件名
 * @param position 文件内偏移量
 */
case class FilePosition(filename: String, position: Long) extends BinlogPosition {
  override def asString: String = s"$filename:$position"
}

object BinlogPosition {
  /**
   * 初始位置（用于快照等没有实际 binlog 位置的场景）
   */
  def initial: BinlogPosition = FilePosition("", 0)
  
  /**
   * 从字符串解析 BinlogPosition
   */
  def parse(str: String): Option[BinlogPosition] = {
    if (str.startsWith("GTID:")) {
      Some(GTIDPosition(str.substring(5)))
    } else {
      str.split(":") match {
        case Array(filename, pos) =>
          pos.toLongOption.map(p => FilePosition(filename, p))
        case _ => None
      }
    }
  }
}
