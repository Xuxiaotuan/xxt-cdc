# ADR-0002: 使用 Pekko Streams 构建 Processing Runtime

## 决策

使用 Apache Pekko Streams 组织 Debezium Event 之后的处理链路。

## 原因

CDC 是长生命周期流处理任务，需要背压、分区、批量、节流、监督和可组合 pipeline。

## 后果

- Runtime pipeline 结构清晰。
- 可以独立调优 batch-size、flush-interval、partition-count。
- 需要注意 Offset/Ack 与异步并行处理之间的边界。
