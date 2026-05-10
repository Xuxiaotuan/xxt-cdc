# Debezium 接入设计

## 为什么使用 Debezium

MySQL binlog 协议、DDL 解析、Schema History、Source Offset 恢复等能力复杂度很高。`xxt-cdc` 不重复实现这些底层捕获能力，而是复用 Debezium 作为 Capture Layer。

这不代表 `xxt-cdc` 不处理 Snapshot/Catchup。Debezium 提供 snapshot/binlog event 和 source offset，`xxt-cdc` 负责把这些事件编排成可恢复的 CDC Runtime 阶段，并维护 Low/High Watermark、下游 apply、ack/checkpoint 和一致性校验。

项目重点放在 Debezium Event 之后的处理链路：

- 事件标准化
- Snapshot/Catchup 阶段编排
- Low/High Watermark 切换
- 表级路由
- 主键 Hash 分区
- 并行 Sink Apply
- 幂等写入
- Offset/Ack 协调
- 运行时观测
- 一致性验证

## 接入模式

当前主路线是 Debezium Engine 嵌入式模式：

```text
xxt-cdc process
  -> Debezium Engine
  -> MySQL Connector
  -> Debezium event
  -> Pekko Streams Runtime
  -> Sink Apply
```

选择 Embedded Engine 的原因：

- 本地 demo 简单，不需要 Kafka / Kafka Connect。
- CDC Runtime 生命周期可以由 xxt-cdc 统一管理。
- 便于在一个进程内控制 event processing、routing、sink apply、ack coordination 和 runtime lifecycle。

生产扩展路线可以是 Kafka Connect + Debezium：

```text
Debezium Connector -> Kafka Topic -> xxt-cdc Consumer -> Pekko Streams -> Sink
```

这个模式更贴近大规模生产环境，但组件更多，项目重点容易被 Kafka Connect runtime 吃掉。

## Debezium 负责什么

- MySQL binlog capture
- Snapshot event 生成
- Source offset 捕获
- Schema history
- SourceRecord / ChangeEvent 生成
- Connector lifecycle 基础能力

## xxt-cdc 负责什么

- Debezium event adapter
- 内部事件模型
- Snapshot / Catchup / Streaming 阶段编排
- Low / High Watermark 记录与切换
- 表过滤
- Hash 分区路由
- 批量缓冲
- 并行 Apply Worker
- Sink 幂等写入
- Sink 成功后的 Ack / checkpoint 协调
- Runtime metadata store
- Metrics / Health / Status API
- Consistency verification

## Snapshot 与 Catchup 边界

Debezium 的 snapshot 能力解决的是“如何从源端捕获全量数据和对应 source offset”。`xxt-cdc` 的 Snapshot/Catchup 解决的是“这些捕获到的事件如何进入下游 runtime 并安全切换到 streaming”。

```text
INIT
  -> record low watermark
  -> SNAPSHOT apply
  -> record high watermark
  -> CATCHUP incremental changes
  -> STREAMING
```

因此，Low/High Watermark 在 `xxt-cdc` 中是运行时切换和一致性校验概念，不是对 MySQL binlog 协议的重新实现。

## Ack 边界

Debezium Engine 的高级批量消费 API 提供 `RecordCommitter`。每条 record 处理完成后调用 `markProcessed(record)`，一个 batch 处理完后调用 `markBatchFinished()`。

`xxt-cdc` 的目标原则：

```text
只有对应事件成功写入 Sink 后，才允许 markProcessed(record)。
只有一个 batch 的事件全部成功 apply 后，才允许 markBatchFinished()。
```

这样可以避免最危险的状态：

```text
Debezium offset 已提交，但目标 Sink 写入失败。
```

如果 Sink 成功但 ack 前进程崩溃，重启后 Debezium 可能重复投递该 batch。目标端通过幂等 Upsert/Delete 吸收重复事件，最终结果收敛。

## 参考

- Debezium Engine: https://debezium.io/documentation/reference/3.4/development/engine.html
- Debezium Server: https://debezium.io/documentation/reference/stable/operations/debezium-server.html
