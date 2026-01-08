package cn.xuyinyin.cdc.model

/**
 * Binlog 位置抽象
 * 支持 GTID 和 File+Position 两种格式
 */
sealed trait BinlogPosition {
  def asString: String
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
