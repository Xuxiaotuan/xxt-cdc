# Snapshot / Catchup / Streaming Cutover

CDC 同步通常不只是消费实时 binlog。对于已经存在数据的表，运行时需要先同步基线数据，再无缝切换到增量流。`xxt-cdc` 将这个过程建模为四个阶段：

```text
INIT -> SNAPSHOT -> CATCHUP -> STREAMING
```

Debezium 提供 source capture 能力，包括 snapshot event、binlog event、source offset 和 schema history。`xxt-cdc` 在此基础上负责运行时阶段编排、Low/High Watermark 记录、下游 apply、ack/checkpoint 协调和一致性验证。

## 阶段说明

| 阶段 | 目标 | 关键动作 |
|------|------|----------|
| `INIT` | 准备任务运行上下文 | 加载配置、初始化 metadata、检查 source/target 表结构 |
| `SNAPSHOT` | 同步全量基线数据 | 接收或执行 snapshot 事件，按内部事件模型写入目标端 |
| `CATCHUP` | 追赶 snapshot 期间产生的增量变更 | 处理 Low/High Watermark 范围内的变更，吸收重复事件 |
| `STREAMING` | 稳定消费实时增量 | 持续消费 binlog event，批量写入目标端并推进 checkpoint |

## Low / High Watermark

Low/High Watermark 是 `xxt-cdc` 的运行时切换概念，用来描述全量阶段和增量阶段之间的边界。

- Low Watermark：进入 snapshot 前记录的 source/runtime 边界。它标识全量同步开始时可能需要追赶的增量起点。
- High Watermark：snapshot 完成后记录的 source/runtime 边界。它标识全量同步结束时需要追赶到的位置。

Debezium 的 source offset 描述底层捕获位置，`xxt-cdc` 的 watermark 描述 runtime 阶段切换和一致性校验边界。两者不是同一层概念，但会在 metadata store 中关联保存。

## Catchup 为什么必要

如果只做全量 snapshot，然后直接进入 streaming，会遇到两个问题：

1. Snapshot 过程中源表仍可能发生 INSERT / UPDATE / DELETE。
2. Snapshot 行和 binlog event 可能覆盖同一主键，写入顺序不受单纯全量扫描保证。

Catchup 阶段用于处理 snapshot 期间产生的增量变更，并在切换到 streaming 前确保目标端状态收敛。

## 一致性原则

`xxt-cdc` 不声明严格 exactly-once。Snapshot/Catchup 的一致性依赖以下机制共同保证：

- 同一表同一主键进入同一 Hash 分区，保持局部顺序。
- Sink 使用 Upsert/Delete 幂等写入，允许重复投递后结果收敛。
- Sink apply 成功后再推进 ack/checkpoint，避免目标端未落库事件被标记为安全点。
- Snapshot 完成后执行 source/target checksum 或行数校验。
- 进程重启后允许重复消费最后一个未确认 batch，由幂等 Sink 吸收。

## 当前状态

当前代码已具备 Snapshot/Catchup 核心流程和高低水位线设计。生产启用前仍建议在真实表结构和数据规模下验证：

- 大表 snapshot 耗时和内存占用。
- Snapshot 期间混合写入的一致性。
- 进程在 snapshot、catchup、ack 前后崩溃时的恢复行为。
- 无主键表、DDL、大事务等边界场景。
