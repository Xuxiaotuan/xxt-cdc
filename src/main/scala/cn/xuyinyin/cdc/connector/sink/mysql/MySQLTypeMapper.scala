package cn.xuyinyin.cdc.connector.sink.mysql

import cn.xuyinyin.cdc.connector._

/**
 * MySQL Sink 类型映射器
 * 
 * 与 Source 的类型映射器相同，但放在 sink 包下以保持独立性
 */
class MySQLTypeMapper extends TypeMapper {
  
  override def toGenericType(nativeType: String): DataType = {
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
}

object MySQLTypeMapper {
  def apply(): MySQLTypeMapper = new MySQLTypeMapper()
}
