# ADR-0001: 使用 Debezium 作为 Capture Layer

## 决策

使用 Debezium MySQL Connector / Debezium Engine 负责 MySQL binlog capture、snapshot、schema history 和 source offset。

## 原因

MySQL binlog 协议、DDL、Snapshot、水位线、schema history 和 offset 恢复复杂度高。项目目标不是重复造 Debezium，而是构建 Debezium Event 之后的 processing runtime。

## 后果

- 项目边界更清晰。
- xxt-cdc 重点放在 event adapter、routing、parallel apply、idempotent sink 和 consistency。
- 需要正确理解 Debezium offset / ack 语义。
