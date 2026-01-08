package cn.xuyinyin.cdc.model

/**
 * 表元数据
 * 
 * @param tableId 表标识
 * @param primaryKeys 主键列名列表
 * @param columns 列元数据列表
 * @param estimatedRows 估计行数
 * @param binlogEnabled 是否启用 binlog
 */
case class TableMeta(
  tableId: TableId,
  primaryKeys: Seq[String],
  columns: Seq[ColumnMeta],
  estimatedRows: Long,
  binlogEnabled: Boolean
)

/**
 * 列元数据
 * 
 * @param name 列名
 * @param dataType 数据类型
 * @param nullable 是否可为空
 * @param defaultValue 默认值
 */
case class ColumnMeta(
  name: String,
  dataType: MySQLDataType,
  nullable: Boolean,
  defaultValue: Option[String]
)

/**
 * 表结构
 * 
 * @param tableId 表标识
 * @param columns 列列表
 * @param primaryKeys 主键列表
 * @param indexes 索引列表
 */
case class TableSchema(
  tableId: TableId,
  columns: Seq[ColumnMeta],
  primaryKeys: Seq[String],
  indexes: Seq[IndexMeta]
)

/**
 * 索引元数据
 * 
 * @param name 索引名
 * @param columns 索引列
 * @param unique 是否唯一索引
 */
case class IndexMeta(
  name: String,
  columns: Seq[String],
  unique: Boolean
)
