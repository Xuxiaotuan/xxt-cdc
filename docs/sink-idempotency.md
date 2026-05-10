# Sink 幂等性

`xxt-cdc` 采用 effectively-once 模型。失败恢复时 Debezium 可能重复投递最后一个未确认 batch，因此 Sink 必须具备幂等写入能力。

## MySQL Sink 策略

### INSERT / Snapshot Read

```sql
INSERT INTO target_table (id, col1, col2)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE
  col1 = VALUES(col1),
  col2 = VALUES(col2);
```

重复投递 INSERT 时，最终结果收敛为相同主键的最新值。

### UPDATE

```sql
UPDATE target_table
SET col1 = ?, col2 = ?
WHERE id = ?;
```

UPDATE 基于主键写入，不依赖 before 值。

### DELETE

```sql
DELETE FROM target_table
WHERE id = ?;
```

重复 DELETE 同一主键时，目标端不存在也视为结果已收敛。

## 前提

- 表必须有主键或稳定唯一键。
- Source 和 Target schema 需要兼容。
- DDL 不由当前 Sink 自动执行。

## 与 Offset 的关系

幂等 Sink 不是为了替代 Offset/Ack 协调，而是为了吸收以下失败窗口：

```text
Sink apply success -> process crash before Debezium ack
```

重启后 Debezium 可能重新投递该事件。Sink 幂等写入保证最终结果一致。
