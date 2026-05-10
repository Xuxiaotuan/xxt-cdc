# 压测方案与结果记录

本文档用于记录 `xxt-cdc` 的可复现压测。当前先给出测试方法和结果模板，真实数据应在固定机器、固定配置和固定数据模型下补充。

## 测试目标

- 验证 Debezium Event -> xxt-cdc Runtime -> Sink Apply 链路吞吐和延迟。
- 验证 Hash 路由和并行 Apply Worker 的扩展效果。
- 验证服务重启后 Debezium Ack / Sink 幂等恢复。
- 验证目标端短暂失败后的重试和幂等收敛。

## 环境记录

| 项目 | 值 |
|------|----|
| CPU | 待填写 |
| 内存 | 待填写 |
| JDK | 17+ |
| Scala | 2.13.14 |
| MySQL Source | 待填写 |
| MySQL Target | 待填写 |
| `partition-count` | 待填写 |
| `apply-worker-count` | 待填写 |
| `batch-size` | 待填写 |
| `commit-interval` | 待填写 |

## 测试场景

| 场景 | 数据量 | Worker 数 | Batch Size | Normalize TPS | Apply TPS | P95 端到端延迟 | Checksum |
|------|-------:|---------:|-----------:|--------------:|----------:|---------------:|----------|
| 单表 INSERT | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 单表混合写入 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 多表混合写入 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 | 待填写 |
| 重启恢复 | 待填写 | 待填写 | 待填写 | - | - | - | 待填写 |
| 目标库短暂停止 | 待填写 | 待填写 | 待填写 | - | - | - | 待填写 |

## 指标定义

| 指标 | 含义 |
|------|------|
| `Normalize TPS` | Debezium event 转内部事件模型的速度 |
| `Apply TPS` | Sink 成功写入速度 |
| `End-to-end latency` | source commit 到 target visible 的延迟 |
| `Ack latency` | Sink 成功到 Debezium ack 的延迟 |
| `Retry count` | Sink 失败重试次数 |
| `Duplicate replay count` | 崩溃恢复后的重复事件数 |
| `Checksum` | source / target 最终一致性校验结果 |

## 推荐压测步骤

1. 使用 `docker-compose.yml` 启动 source / target / prometheus。
2. 初始化源表和目标表结构。
3. 启动 `xxt-cdc`，确认 `/health` 为可用。
4. 用脚本向源库批量写入 INSERT / UPDATE / DELETE。
5. 记录 `/metrics`、目标端行数、Offset Store 位置。
6. 在写入过程中重启服务，确认目标端最终行数一致。
7. 临时停止目标库或断开网络，确认服务不提交未成功写入的位置。

## 结果解释原则

压测结果不需要追求夸张数字，更重要的是能解释：

- 为什么增加 worker 后吞吐提升会有上限。
- 为什么单表同主键更新无法无限并行。
- 为什么 batch size 变大可能提升吞吐但增加延迟。
- 为什么 Offset 提交频率影响恢复时重复消费窗口。
