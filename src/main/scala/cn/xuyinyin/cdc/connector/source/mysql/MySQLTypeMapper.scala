package cn.xuyinyin.cdc.connector.source.mysql

import cn.xuyinyin.cdc.connector._
import cn.xuyinyin.cdc.model.{MySQLDataType, Blob, Char, Date, DateTime, Decimal, Double, Float, Int, Json, LongText, Text, Time, Timestamp, TinyInt, SmallInt, MediumInt, BigInt, VarChar}

/**
 * MySQL 类型映射器
 * 
 * 负责在 MySQL 特定类型和通用类型之间进行转换
 */
class MySQLTypeMapper extends TypeMapper {
  
  /**
   * 将 MySQL 类型转换为通用类型
   */
  override def toGenericType(nativeType: String): DataType = {
    // 解析 MySQL 类型字符串（如 "VARCHAR(255)", "DECIMAL(10,2)"）
    val typePattern = """(\w+)(?:\((\d+)(?:,(\d+))?\))?""".r
    
    nativeType.toUpperCase match {
      case typePattern(baseType, length, scale) =>
        baseType match {
          case "TINYINT" => TinyIntType
          case "SMALLINT" => SmallIntType
          case "MEDIUMINT" | "INT" | "INTEGER" => IntType
          case "BIGINT" => BigIntType
          case "DECIMAL" | "NUMERIC" =>
            val p = Option(length).map(_.toInt).getOrElse(10)
            val s = Option(scale).map(_.toInt).getOrElse(0)
            DecimalType(p, s)
          case "FLOAT" => FloatType
          case "DOUBLE" | "REAL" => DoubleType
          case "VARCHAR" =>
            val len = Option(length).map(_.toInt).getOrElse(255)
            VarCharType(len)
          case "CHAR" =>
            val len = Option(length).map(_.toInt).getOrElse(1)
            CharType(len)
          case "TEXT" | "TINYTEXT" | "MEDIUMTEXT" => TextType
          case "LONGTEXT" => LongTextType
          case "BLOB" | "TINYBLOB" | "MEDIUMBLOB" | "LONGBLOB" => BlobType
          case "BINARY" | "VARBINARY" =>
            val len = Option(length).map(_.toInt).getOrElse(255)
            VarBinaryType(len)
          case "DATE" => DateType
          case "TIME" => TimeType
          case "DATETIME" => DateTimeType
          case "TIMESTAMP" => TimestampType
          case "BOOLEAN" | "BOOL" => BooleanType
          case "JSON" => JsonType
          case _ => UnknownType(nativeType)
        }
      case _ => UnknownType(nativeType)
    }
  }
  
  /**
   * 将通用类型转换为 MySQL 类型
   */
  override def toNativeType(genericType: DataType): String = {
    genericType match {
      case TinyIntType => "TINYINT"
      case SmallIntType => "SMALLINT"
      case IntType => "INT"
      case BigIntType => "BIGINT"
      case DecimalType(p, s) => s"DECIMAL($p,$s)"
      case FloatType => "FLOAT"
      case DoubleType => "DOUBLE"
      case VarCharType(len) => s"VARCHAR($len)"
      case CharType(len) => s"CHAR($len)"
      case TextType => "TEXT"
      case LongTextType => "LONGTEXT"
      case BlobType => "BLOB"
      case VarBinaryType(len) => s"VARBINARY($len)"
      case DateType => "DATE"
      case TimeType => "TIME"
      case DateTimeType => "DATETIME"
      case TimestampType => "TIMESTAMP"
      case BooleanType => "BOOLEAN"
      case JsonType => "JSON"
      case UnknownType(original) => original
    }
  }
  
  /**
   * 将 MySQLDataType 转换为通用类型
   */
  def fromMySQLDataType(mysqlType: MySQLDataType): DataType = {
    mysqlType match {
      case TinyInt => TinyIntType
      case SmallInt => SmallIntType
      case MediumInt | Int => IntType
      case BigInt => BigIntType
      case Decimal(p, s) => DecimalType(p, s)
      case Float => FloatType
      case Double => DoubleType
      case VarChar(len) => VarCharType(len)
      case Char(len) => CharType(len)
      case Text => TextType
      case LongText => LongTextType
      case Blob => BlobType
      case Date => DateType
      case Time => TimeType
      case DateTime => DateTimeType
      case Timestamp => TimestampType
      case Json => JsonType
    }
  }
  
  /**
   * 将通用类型转换为 MySQLDataType
   */
  def toMySQLDataType(genericType: DataType): MySQLDataType = {
    genericType match {
      case TinyIntType => TinyInt
      case SmallIntType => SmallInt
      case IntType => Int
      case BigIntType => BigInt
      case DecimalType(p, s) => Decimal(p, s)
      case FloatType => Float
      case DoubleType => Double
      case VarCharType(len) => VarChar(len)
      case CharType(len) => Char(len)
      case TextType => Text
      case LongTextType => LongText
      case BlobType => Blob
      case DateType => Date
      case TimeType => Time
      case DateTimeType => DateTime
      case TimestampType => Timestamp
      case BooleanType => TinyInt // MySQL 使用 TINYINT(1) 表示布尔
      case JsonType => Json
      case UnknownType(_) => Text // 未知类型默认为 TEXT
    }
  }
}

object MySQLTypeMapper {
  def apply(): MySQLTypeMapper = new MySQLTypeMapper()
}
