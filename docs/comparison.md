# 对比说明

`xxt-cdc` 不与 Debezium 竞争。Debezium 是 Capture Layer，xxt-cdc 是基于 Debezium Event 的 Processing Runtime。

| 项目 | 定位 | xxt-cdc 的关系 |
|------|------|----------------|
| Debezium | CDC Capture Layer | xxt-cdc 复用它作为变更捕获层 |
| Kafka Connect Sink | 标准 Connector Runtime | xxt-cdc 提供更轻量、更可定制的 Sink Processing Runtime |
| Flink CDC | Flink 生态 CDC Source | xxt-cdc 不依赖 Flink Runtime，更适合轻量服务化场景 |
| Canal | MySQL binlog 订阅组件 | xxt-cdc 不重复做 binlog 解析，选择 Debezium 生态 |
| SeaTunnel | 数据集成平台 | xxt-cdc 更聚焦 CDC Event 到 Sink 的运行时链路 |

## 为什么不是 Kafka Connect Sink

Kafka Connect Sink 是成熟的标准路线，适合生产平台化场景。xxt-cdc 的目标更轻：

- 进程内嵌入 Debezium Engine。
- 用 Pekko Streams 控制 event processing pipeline。
- 更容易展示 routing、parallel apply、idempotent sink、ack coordination 的实现。

## 为什么不是 Flink CDC

Flink CDC 适合已有 Flink Runtime 的实时计算平台。xxt-cdc 的目标是轻量级服务化 runtime，不要求用户部署 Flink 集群。
