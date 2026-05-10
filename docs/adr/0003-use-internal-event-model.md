# ADR-0003: 使用内部事件模型隔离 Debezium

## 决策

Debezium 原始事件只允许出现在 adapter 层，Runtime 和 Sink 层消费内部 `ChangeEvent` / `RowChangeEvent`。

## 原因

内部模型可以隔离 Debezium 输出格式变化，也方便后续支持其他 source connector。

## 后果

- Sink 不依赖 Debezium。
- Routing 和 consistency 文档可以围绕领域模型展开。
- Adapter 层需要维护 Debezium 到内部模型的转换测试。
