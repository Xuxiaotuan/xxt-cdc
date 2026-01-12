package cn.xuyinyin.cdc.connector.sink.starrocks

import cn.xuyinyin.cdc.connector._

/**
 * StarRocks 类型映射器
 * 
 * 负责在 StarRocks 特定类型和通用类型之间进行转换
 */
class StarRocksTypeMapper extends TypeMapper {
  
  override def toGenericType(nativeType: String): DataType = {
    val typePattern = """(\w+)(?:\((\d+)(?:,(\d+))?\))?""".r
    
    nativeType.toUpperCase match {
      case typePattern(baseType, length, scale) =>
        baseType match {
          case "TINYINT" => TinyIntType
          case "SMALLINT" => SmallIntType
          case "INT" | "INTEGER" => IntType
          case "BIGINT" => BigIntType
          case "LARGEINT" => BigIntType
          case "DECIMAL" | "DECIMALV2" | "DECIMAL32" | "DECIMAL64" | "DECIMAL128" =>
            val p = Option(length).map(_.toInt).getOrElse(10)
            val s = Option(scale).map(_.toInt).getOrElse(0)
            DecimalType(p, s)
          case "FLOAT" => FloatType
          case "DOUBLE" => DoubleType
          case "VARCHAR" =>
            val len = Option(length).map(_.toInt).getOrElse(65533)
            VarCharType(len)
          case "CHAR" =>
            val len = Option(length).map(_.toInt).getOrElse(1)
            CharType(len)
          case "STRING" => TextType
          case "DATE" => DateType
          case "DATETIME" => DateTimeType
          case "BOOLEAN" => BooleanType
          case "JSON" => JsonType
          case "ARRAY" | "MAP" | "STRUCT" => JsonType
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
      case DecimalType(p, s) =>
        if (p <= 9) s"DECIMAL32($p,$s)"
        else if (p <= 18) s"DECIMAL64($p,$s)"
        else s"DECIMAL128($p,$s)"
      case FloatType => "FLOAT"
      case DoubleType => "DOUBLE"
      case VarCharType(len) => s"VARCHAR($len)"
      case CharType(len) => s"CHAR($len)"
      case TextType | LongTextType => "STRING"
      case BlobType => "STRING"
      case VarBinaryType(_) => "STRING"
      case DateType => "DATE"
      case TimeType => "STRING"
      case DateTimeType | TimestampType => "DATETIME"
      case BooleanType => "BOOLEAN"
      case JsonType => "JSON"
      case UnknownType(original) => original
    }
  }
  
  override def convertValue(value: Any, sourceType: DataType, targetType: DataType): Any = {
    if (value == null) return null
    
    (sourceType, targetType) match {
      case (_: TemporalType, _: TemporalType) => value
      case (BlobType, TextType | LongTextType) =>
        value match {
          case bytes: Array[Byte] => new String(bytes, "UTF-8")
          case _ => value.toString
        }
      case _ => super.convertValue(value, sourceType, targetType)
    }
  }
}

object StarRocksTypeMapper {
  def apply(): StarRocksTypeMapper = new StarRocksTypeMapper()
}
