# 故障恢复与一致性边界

`xxt-cdc` 的容错目标是：服务重启、目标库短暂失败或网络抖动时不丢数据，并通过幂等写入处理可能发生的重复消费。

## 故障场景

| 场景 | 处理方式 | 结果 |
|------|----------|------|
| 服务进程重启 | Debezium 从 source offset 恢复，xxt-cdc 从 sink checkpoint 恢复 | 未确认 batch 可能重复投递 |
| Sink 成功但 Ack 前崩溃 | Debezium 可能重新投递 | 依赖幂等 Sink 收敛 |
| Event 收到但 Sink 未成功 | 不 ack Debezium record | 重启后继续投递，不丢失 |
| 目标库短暂不可用 | Worker 写入失败，错误处理和监督策略介入 | 不确认未成功写入的 record |
| Worker 批次内部分失败 | 记录失败事件，批次结果参与 Ack 判断 | 避免把失败位置标记为安全点 |
| DDL 变更 | DDL 检测和告警 | 默认不自动同步结构 |
| Metadata DB 不可用 | Runtime checkpoint 保存失败 | 保留未提交状态，后续重试或人工介入 |

## 一致性模型

项目采用 effectively-once 语义，而不是严格 exactly-once：

1. Debezium 负责 source capture 和 source offset。
2. Adapter 将 Debezium event 转换为内部事件。
3. Router 保证同表同主键进入同一分区。
4. Worker 成功写入后才允许 Ack Debezium record。
5. 重启后允许未确认 batch 重复投递，重复事件由幂等 Sink 处理。

这个模型适合 MySQL 到 MySQL / OLAP 的同步场景，因为目标端常见写入方式可以通过主键 Upsert 达到最终一致。

## DDL 边界

DDL 自动同步风险较高：字段删除、类型变更、索引变更和目标端兼容性都可能导致不可逆错误。因此当前项目把 DDL 作为检测和告警事件处理，不默认自动修改目标库结构。

推荐生产策略：

- DDL 由变更流程控制，先改目标端兼容结构。
- CDC 服务检测到 DDL 后告警。
- 对高风险 DDL 暂停任务，确认目标端 schema 后恢复。

## Snapshot/Catchup 边界

Snapshot 由 Debezium 负责 capture，xxt-cdc 负责 snapshot read event 的下游 apply。全量一致性和大表性能强依赖表结构、主键分布、Debezium snapshot 配置和目标端吞吐。生产启用前应至少验证：

- 大表分片耗时。
- Snapshot 期间持续写入时 Debezium 后续增量事件是否完整投递。
- Snapshot read event 和 streaming event 的目标端幂等写入是否收敛。
- 目标端重复写入是否可收敛。
