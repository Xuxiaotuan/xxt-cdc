# 一致性验证报告

本文档记录 `xxt-cdc` 的一致性验证方法。当前先提供可复现的验证方案和结果表，真实结果应在固定环境中执行 `scripts/demo.sh`、`scripts/check-consistency.sh` 和故障恢复步骤后填写。

## 测试环境

| 项目 | 值 |
|------|----|
| 机器 | 待填写 |
| Docker | 待填写 |
| MySQL | 8.0 |
| Source DB | `test` |
| Target DB | `test_target` |
| Offset Store | MySQL metadata DB `xxt_cdc` |

## 验证场景

| 场景 | 数据量 | 操作方式 | 预期结果 | 实际结果 | 结论 |
|------|-------:|----------|----------|----------|------|
| 纯 INSERT | 待填写 | 批量插入源表 | 目标表行数和 checksum 一致 | 待填写 | 待填写 |
| INSERT + UPDATE + DELETE 混合 | 待填写 | 写入、更新、删除交错执行 | 最终行集一致 | 待填写 | 待填写 |
| Snapshot 期间增量写入 | 待填写 | Snapshot 运行时持续写源库 | Catchup 后一致 | 待填写 | 待填写 |
| Apply Worker 中途失败 | 待填写 | 暂停目标库或制造写入失败 | 不提交失败位置 | 待填写 | 待填写 |
| 进程重启恢复 | 待填写 | 写入中重启 CDC 进程 | 从 Offset 恢复，无丢失 | 待填写 | 待填写 |

## 校验 SQL

```sql
SELECT COUNT(*) FROM users;
CHECKSUM TABLE users;

SELECT COUNT(*) FROM orders;
CHECKSUM TABLE orders;
```

也可以直接运行：

```bash
bash scripts/check-consistency.sh
```

## 判定标准

- Source 和 Target 的行数一致。
- Source 和 Target 的 checksum 一致。
- 重启后 Offset Store 中 `task_name` 对应的位置继续推进。
- 目标端没有重复主键异常。
- CDC 错误指标和日志中没有未处理失败。
