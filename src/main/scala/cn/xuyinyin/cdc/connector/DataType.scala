package cn.xuyinyin.cdc.connector

/**
 * 通用数据类型系统
 * 
 * 提供数据库无关的类型抽象，支持不同数据库之间的类型映射。
 * 所有 Connector 都使用这套通用类型系统进行数据交换。
 */
sealed trait DataType {
  /**
   * 获取类型的显示名称
   */
  def displayName: String
}

/**
 * 整数类型
 */
sealed trait IntegerType extends DataType

case object TinyIntType extends IntegerType {
  override def displayName: String = "TINYINT"
}

case object SmallIntType extends IntegerType {
  override def displayName: String = "SMALLINT"
}

case object IntType extends IntegerType {
  override def displayName: String = "INT"
}

case object BigIntType extends IntegerType {
  override def displayName: String = "BIGINT"
}

/**
 * 浮点数类型
 */
sealed trait FloatingType extends DataType

case object FloatType extends FloatingType {
  override def displayName: String = "FLOAT"
}

case object DoubleType extends FloatingType {
  override def displayName: String = "DOUBLE"
}

/**
 * 定点数类型
 * @param precision 精度（总位数）
 * @param scale 小数位数
 */
case class DecimalType(precision: Int, scale: Int) extends DataType {
  override def displayName: String = s"DECIMAL($precision,$scale)"
}

/**
 * 字符串类型
 */
sealed trait StringType extends DataType

case class VarCharType(length: Int) extends StringType {
  override def displayName: String = s"VARCHAR($length)"
}

case class CharType(length: Int) extends StringType {
  override def displayName: String = s"CHAR($length)"
}

case object TextType extends StringType {
  override def displayName: String = "TEXT"
}

case object LongTextType extends StringType {
  override def displayName: String = "LONGTEXT"
}

/**
 * 二进制类型
 */
sealed trait BinaryType extends DataType

case object BlobType extends BinaryType {
  override def displayName: String = "BLOB"
}

case class VarBinaryType(length: Int) extends BinaryType {
  override def displayName: String = s"VARBINARY($length)"
}

/**
 * 日期时间类型
 */
sealed trait TemporalType extends DataType

case object DateType extends TemporalType {
  override def displayName: String = "DATE"
}

case object TimeType extends TemporalType {
  override def displayName: String = "TIME"
}

case object DateTimeType extends TemporalType {
  override def displayName: String = "DATETIME"
}

case object TimestampType extends TemporalType {
  override def displayName: String = "TIMESTAMP"
}

/**
 * 布尔类型
 */
case object BooleanType extends DataType {
  override def displayName: String = "BOOLEAN"
}

/**
 * JSON 类型
 */
case object JsonType extends DataType {
  override def displayName: String = "JSON"
}

/**
 * 未知类型（用于不支持的类型）
 * @param originalType 原始类型名称
 */
case class UnknownType(originalType: String) extends DataType {
  override def displayName: String = s"UNKNOWN($originalType)"
}

/**
 * 类型映射器接口
 * 
 * 负责在通用类型和特定数据库类型之间进行转换
 */
trait TypeMapper {
  /**
   * 将数据库特定类型转换为通用类型
   * 
   * @param nativeType 数据库原生类型描述
   * @return 通用数据类型
   */
  def toGenericType(nativeType: String): DataType
  
  /**
   * 将通用类型转换为数据库特定类型
   * 
   * @param genericType 通用数据类型
   * @return 数据库原生类型描述
   */
  def toNativeType(genericType: DataType): String
  
  /**
   * 转换数据值
   * 
   * @param value 原始值
   * @param sourceType 源类型
   * @param targetType 目标类型
   * @return 转换后的值
   */
  def convertValue(value: Any, sourceType: DataType, targetType: DataType): Any = {
    // 默认实现：如果类型相同或兼容，直接返回
    if (sourceType == targetType || isCompatible(sourceType, targetType)) {
      value
    } else {
      // 需要子类实现具体的转换逻辑
      throw new UnsupportedOperationException(
        s"Type conversion from ${sourceType.displayName} to ${targetType.displayName} is not supported"
      )
    }
  }
  
  /**
   * 检查两个类型是否兼容
   */
  protected def isCompatible(sourceType: DataType, targetType: DataType): Boolean = {
    (sourceType, targetType) match {
      case (_: IntegerType, _: IntegerType) => true
      case (_: FloatingType, _: FloatingType) => true
      case (_: StringType, _: StringType) => true
      case (_: BinaryType, _: BinaryType) => true
      case (_: TemporalType, _: TemporalType) => true
      case _ => false
    }
  }
}
