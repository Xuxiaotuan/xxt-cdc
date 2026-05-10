# 事件模型

Debezium 的事件结构包含 source metadata、schema、before/after、operation、transaction 等信息。`xxt-cdc` 不让 Debezium 原始对象在运行时各层扩散，而是将其转换为稳定的内部事件模型。

## 当前模型

当前代码中内部事件模型是 `ChangeEvent`：

```scala
case class ChangeEvent(
  tableId: TableId,
  operation: Operation,
  primaryKey: Map[String, Any],
  before: Option[Map[String, Any]],
  after: Option[Map[String, Any]],
  timestamp: Instant,
  position: BinlogPosition
)
```

它已经满足 routing、apply、offset coordination 的基础需求。

## 目标模型

后续可以将 `ChangeEvent` 演进为更明确的 `RowChangeEvent`：

```scala
sealed trait CdcOperation
object CdcOperation {
  case object Insert extends CdcOperation
  case object Update extends CdcOperation
  case object Delete extends CdcOperation
  case object SnapshotRead extends CdcOperation
}

final case class CdcOffset(
  file: Option[String],
  position: Option[Long],
  gtid: Option[String],
  snapshot: Boolean,
  raw: Map[String, String]
)

final case class RowChangeEvent(
  table: TableId,
  operation: CdcOperation,
  before: Map[String, Any],
  after: Map[String, Any],
  primaryKey: Map[String, Any],
  offset: CdcOffset,
  eventTimeMs: Long,
  sourceTimeMs: Long,
  transactionId: Option[String]
)
```

## 设计原则

- Debezium object 只出现在 adapter 层。
- Routing 层只依赖 table + primaryKey。
- Sink 层只依赖内部事件模型，不依赖 Debezium。
- Offset 层保留 Debezium source offset 的 raw 信息，方便故障排查。
- Snapshot read 事件要和 Insert 区分，便于统计和一致性验证。

## 为什么需要内部模型

内部模型的价值在于隔离变化：

- Debezium 输出格式变化不会影响 Sink 层。
- 多种 source connector 可以统一成同一个 processing model。
- 多种 sink connector 可以复用 routing、batch、retry、metrics。
- 设计文档可以围绕领域模型讲清楚，而不是围绕第三方对象讲实现细节。
