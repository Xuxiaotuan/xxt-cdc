# 一致性验证报告

本文档记录 `xxt-cdc` 的一致性验证范围、自动化进度与手动验证步骤。

## 覆盖矩阵

| # | 场景 | 自动化 | 测试 / 脚本 | 状态 |
|---|------|--------|------------|------|
| S1 | 增量 INSERT 端到端 + ack 闭环 | ✅ | `RealDebeziumPipelineSpec` | 通过 |
| S2 | INSERT + UPDATE + DELETE 混合 | ❌ | 手动（见 §S2） | 未自动化 |
| S3 | Snapshot 期间增量写入（catchup） | ❌ | 手动（见 §S3） | 未自动化 |
| S4 | Apply Worker 中途失败 | ❌ | 手动 fault injection（见 §S4） | 未自动化 |
| S5 | 进程重启恢复（offset resume） | ❌ | 手动（见 §S5） | 未自动化 |
| S6 | 单实例锁互斥 | ✅ | `MySQLSingletonLockSpec` | 通过 |
| S7 | 大批量压力 / 长跑 | ❌ | `scripts/demo.sh` + `BENCHMARK.md` | 待补 |

> 状态约定：✅ = CI 自动跑且通过，❌ = 需要人工介入。
> 当前 P0 重点是 S1（增量正确性 + ack 闭环），其他场景在后续迭代逐步自动化。

## 测试环境

| 项目 | 值 |
|------|----|
| MySQL | 8.0（testcontainers，binlog ROW + GTID） |
| 容器引擎 | Docker / OrbStack |
| Source DB | `source_db` |
| Target DB | `target_db` |
| Offset Store | `FileOffsetStore`（测试） / `MySQLOffsetStore`（生产推荐） |

## S1：增量 INSERT 端到端（已自动化）

**位置**：`src/test/scala/cn/xuyinyin/cdc/integration/RealDebeziumPipelineSpec.scala`

**装配**：testcontainers 起单 MySQL 容器，同实例两库（source/target），手动 wire 全 pipeline 组件等价 `CDCEngine` 内部装配。Debezium `snapshot.mode=never`，只验证增量。

**步骤**：

1. 启动容器、建表、授权
2. `pipeline.run(...)` 启动 Debezium engine（异步）
3. 等 8s 让 engine 完成连接 + binlog tail 就绪
4. 向 `source_db.orders` 批量 INSERT 20 行（id 1–20）
5. 轮询断言至 60 秒上限

**关键断言**：

- `rowCount(target) == 20` — 数据复制完整
- `reader.ackPendingCount() == 0` — **commit 后 AckRegistry 清零**，证明 `commitAndAck → DebeziumBinlogReader.ack(recordId)` 闭环真生效，没有泄漏
- `offsetCoordinator.getLastCommittedPosition()` 已定义 — checkpoint 已落
- `MD5(GROUP_CONCAT(id|name|amount ORDER BY id))` source == target — 内容（含精度）一致

**已知约束**：

- 仅覆盖 INSERT，未含 UPDATE/DELETE 混合时序
- 单 partition 数 = 4，未压测高并发
- 单表单 PK，未覆盖复合 PK / 无 PK 表

## S2：INSERT + UPDATE + DELETE 混合（手动）

**目的**：验证同一 PK 在多操作下顺序保留（路由器 `hash(table+pk)` 保证同 PK 进同 partition，ApplyWorker 内严格串行）。

**步骤**：

```sql
-- 在 source_db.orders 上跑
INSERT INTO orders VALUES (1,'a',100), (2,'b',200);
UPDATE orders SET name='a2' WHERE id=1;
DELETE FROM orders WHERE id=2;
UPDATE orders SET amount=999 WHERE id=1;
```

**判定**：等待 ≥ `commitInterval` 后，target 应有 `(1,'a2',999)`，无 id=2。

## S3：Snapshot 期间增量写入（手动）

**目的**：snapshot 阶段（`snapshotMode=initial`）持续写源库，catchup 后一致。

**前置**：source 表已有 ≥ 10000 行，配置 `snapshotMode=initial`，`enableSnapshot=true`。

**步骤**：

1. 启动 CDC（snapshot 自动跑）
2. snapshot 期间向 source 持续 INSERT/UPDATE
3. 等 snapshot done → 增量 catchup 完成
4. 检查行数 + MD5 一致

## S4：Apply Worker 中途失败（手动 fault injection）

**目的**：apply 异常时 pipeline 失败终止，不提交失败位置（不丢不错位）。

**步骤**：

1. 起 CDC 同步
2. `REVOKE INSERT ON target_db.orders FROM 'cdc'` 制造写失败，或停掉 target MySQL
3. 向 source INSERT
4. 观察 pipeline 抛异常 + Future[Done] 失败
5. 检查 `OffsetCoordinator.getLastCommittedPosition` **不前进**到失败 record
6. 恢复 target 后重启，从上次 commit position 续跑，最终一致

## S5：进程重启恢复（手动）

**目的**：crash recovery 不丢不重。

**步骤**：

1. 起 CDC 同步若干 INSERT，确认 `commit` 写入 OffsetStore
2. `kill -9` CDC 进程
3. 期间向 source 继续 INSERT
4. 重启 CDC（`startFromLatest=false`），从 OffsetStore.load() 恢复
5. 检查所有事件最终都到 target，无重复（PK 唯一约束）

## S6：单实例锁互斥（已自动化）

**位置**：`src/test/scala/cn/xuyinyin/cdc/cluster/MySQLSingletonLockSpec.scala`

覆盖：acquire/renew/release 语义、过期检测、跨 task 隔离、并发选举单赢家。

## S7：大批量压力 / 长跑（脚本）

参见 [`BENCHMARK.md`](./BENCHMARK.md)。`scripts/demo.sh` + `scripts/check-consistency.sh` 提供本地手动跑的入口。

## 校验 SQL

```sql
-- 行数一致
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM users; -- 在 target_db

-- 内容一致（带顺序 + 精度的 MD5）
SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', id, name, amount) ORDER BY id SEPARATOR ';'))
FROM orders;

-- MySQL 自带 checksum
CHECKSUM TABLE orders;
```

或运行：

```bash
bash scripts/check-consistency.sh
```

## 判定标准

- Source / Target 行数一致
- Source / Target MD5（或 CHECKSUM TABLE）一致
- `reader.ackPendingCount() == 0`（commit 后 ack 不泄漏，**S1 已自动化**）
- `OffsetStore` 中 `task_name` 对应位置在重启后能恢复并继续推进
- 目标端无重复 PK 异常
- CDC error metrics 与日志无未处理 failure

## 自动化路线图

| 优先级 | 场景 | 实现思路 |
|--------|------|----------|
| P0 ✅ | S1 增量 INSERT + ack 闭环 | 已实现 |
| P1 | S2 混合 ops | 在 `RealDebeziumPipelineSpec` 加 case，验证同 PK 顺序 |
| P1 | S5 重启恢复 | 起 / 停 / 起 pipeline，跨 process 复用 `FileOffsetStore` |
| P2 | S4 fault injection | 注入 `DataWriter` mock 抛异常，断言 pipeline failed + offset 不前进 |
| P2 | S3 snapshot+catchup | 需要 snapshot path 的实现完善，再补集成 |
