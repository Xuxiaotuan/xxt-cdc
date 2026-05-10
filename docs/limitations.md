# 限制与边界

## Debezium 相关边界

- MySQL binlog 读取、Snapshot、Schema History 由 Debezium 负责。
- xxt-cdc 不直接解析 MySQL binlog。
- Debezium connector 配置、server-id、binlog retention、权限配置需要用户正确提供。
- 如果 Debezium source offset / schema history 丢失，可能需要重新 snapshot 或人工恢复。

## DDL

- xxt-cdc 当前不自动将 DDL 应用到目标库。
- Debezium 可以捕获 schema change，但目标端 schema evolution 暂不自动执行。
- 当前建议用户提前保证 source / target 表结构兼容。

## 无主键表

- 不建议同步无主键表。
- 原因：幂等 Upsert/Delete 和 Hash 路由依赖主键或稳定唯一键。

## Exactly-once

- 不声明严格 exactly-once。
- 当前目标是 Debezium Ack + Sink 幂等写入形成 effectively-once。

## 大事务

- 大事务会造成单批事件积压和 Sink 写入压力。
- 当前通过 batch-size、flush-interval 和 backpressure 降低风险，但未实现事务级 spill。

## 集群扩展

- 当前主路线是单进程 Embedded Engine。
- 如果需要多实例 connector 容错和大规模扩展，建议评估 Kafka Connect + Debezium 部署模式。
