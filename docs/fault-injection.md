# 故障注入计划

故障注入重点验证 Debezium Ack 边界、Sink 幂等写入和最终一致性。

| 场景 | 操作 | 观察点 | 预期 |
|------|------|--------|------|
| Sink apply 前 kill xxt-cdc | 收到 event 后、写目标库前杀进程 | Debezium 是否重新投递 | 不丢失 |
| Sink apply 后 ack 前 kill xxt-cdc | 写目标库成功后、Ack 前杀进程 | 是否重复投递，target 是否重复 | 允许重复投递，target 收敛 |
| Target MySQL 暂停 30 秒 | 暂停 target 容器 | 是否提前 ack，错误指标是否上升 | 不提交失败位置 |
| Debezium offset storage 删除后重启 | 删除 offset 文件或表 | 是否需要重新 snapshot | 明确失败或人工恢复 |
| Schema history storage 删除后重启 | 删除 schema history | Debezium 是否可恢复 schema | 明确失败或重新 snapshot |
| 同一主键连续更新期间 kill | 对同一 id 连续 update 并杀进程 | target 最终值 | 最终值一致 |
| Snapshot 期间写入源表 | Snapshot 时插入/更新/删除 | Catchup 后 checksum | 最终一致 |

## 输出项

每个场景记录：

- Debezium 是否重复投递。
- xxt-cdc 是否重复 apply。
- target 是否最终一致。
- source offset / sink checkpoint 是否正确推进。
- row count / checksum 是否一致。

## 判定原则

```text
可以重复投递，但不能丢数据。
可以重复 apply，但目标端必须幂等收敛。
禁止 Sink 失败但 Debezium offset 已确认。
```
