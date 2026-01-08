package cn.xuyinyin.cdc.model

/**
 * MySQL 数据类型映射
 */
sealed trait MySQLDataType

case object TinyInt extends MySQLDataType
case object SmallInt extends MySQLDataType
case object MediumInt extends MySQLDataType
case object Int extends MySQLDataType
case object BigInt extends MySQLDataType
case class Decimal(precision: Int, scale: Int) extends MySQLDataType
case object Float extends MySQLDataType
case object Double extends MySQLDataType
case class VarChar(length: Int) extends MySQLDataType
case class Char(length: Int) extends MySQLDataType
case object Text extends MySQLDataType
case object LongText extends MySQLDataType
case object DateTime extends MySQLDataType
case object Timestamp extends MySQLDataType
case object Date extends MySQLDataType
case object Time extends MySQLDataType
case object Json extends MySQLDataType
case object Blob extends MySQLDataType
