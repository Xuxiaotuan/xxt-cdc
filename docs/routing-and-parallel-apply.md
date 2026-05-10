# 路由与并行 Apply

CDC Runtime 需要同时满足两个目标：

- 尽量并行写入，提高吞吐。
- 同一主键的变更保持顺序，避免旧事件覆盖新事件。

## Hash 路由

`xxt-cdc` 使用 `hash(table + primary key) % partitionCount` 将事件路由到固定分区。

```text
same table + same primary key -> same partition -> same worker order
different primary keys -> different partitions -> parallel apply
```

## Apply Worker

每个分区内事件按顺序进入对应 worker。Worker 使用 batch 配置控制吞吐和延迟：

- `batch-size`
- `flush-interval`
- `apply-worker-count`
- `partition-count`

## 设计取舍

- 分区数越多，热点主键冲突越少，但 Offset 协调和内存状态更多。
- Batch 越大，吞吐通常越高，但端到端延迟可能上升。
- 同一主键不能无限并行，这是 CDC 顺序一致性的必要代价。

## 测试覆盖

当前单元测试覆盖：

- 同表同主键稳定路由到同一分区。
- 路由结果始终在合法分区范围内。
- 非法分区数量会失败。
