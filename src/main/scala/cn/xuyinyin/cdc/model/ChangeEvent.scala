package cn.xuyinyin.cdc.model

import java.time.Instant

/**
 * 标准化的数据变更事件
 * 
 * @param tableId 表标识
 * @param operation 操作类型
 * @param primaryKey 主键值映射
 * @param before 变更前的数据（UPDATE/DELETE 时有值）
 * @param after 变更后的数据（INSERT/UPDATE 时有值）
 * @param timestamp 事件时间戳
 * @param position binlog 位置
 */
case class ChangeEvent(
  tableId: TableId,
  operation: Operation,
  primaryKey: Map[String, Any],
  before: Option[Map[String, Any]],
  after: Option[Map[String, Any]],
  timestamp: Instant,
  position: BinlogPosition
)

/**
 * 操作类型
 */
sealed trait Operation
case object Insert extends Operation
case object Update extends Operation
case object Delete extends Operation
