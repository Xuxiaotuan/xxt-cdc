# ADR-0004: Sink 成功后再 Ack Debezium Record

## 决策

Debezium record 的处理确认必须晚于 Sink apply 成功。

## 原因

如果 Debezium source offset 先提交，而目标端写入失败，系统会出现不可恢复的数据丢失。

## 后果

- Sink 成功但 ack 前崩溃时，重启后可能重复投递。
- Sink 必须幂等。
- 该模型不声明严格 exactly-once，而是以 effectively-once 为目标。
