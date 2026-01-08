package cn.xuyinyin.cdc.model

/**
 * 表标识符，唯一标识一个数据库表
 * 
 * @param database 数据库名
 * @param table 表名
 */
case class TableId(database: String, table: String) {
  override def toString: String = s"$database.$table"
}
