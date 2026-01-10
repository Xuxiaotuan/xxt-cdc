# 快照和 Catchup 机制

本文档说明 MySQL CDC 服务中的快照（Snapshot）和追赶（Catchup）机制。

## 概述

CDC 系统需要处理两种数据同步场景：

1. **全量同步（Snapshot）**：为已存在的表创建一致性快照
2. **增量同步（Streaming）**：实时处理 binlog 事件

快照和 Catchup 机制确保了从全量到增量的无缝过渡，保证数据一致性。

## 核心概念

### Low Watermark（低水位标记）

- **定义**：快照开始时的 binlog 位置
- **作用**：标记快照数据的一致性点
- **记录时机**：在开始快照之前

### High Watermark（高水位标记）

- **定义**：快照完成时的 binlog 位置
- **作用**：标记 Catchup 的结束点
- **记录时机**：在所有快照完成之后

### Catchup（追赶）

- **定义**：从 Low Watermark 到 High Watermark 之间的 binlog 重放
- **作用**：弥补快照期间发生的数据变更
- **处理方式**：读取 binlog 并应用到目标数据库

## CDC 生命周期

```
INIT → SNAPSHOT → CATCHUP → STREAMING
```

### 1. INIT（初始化）

- 初始化所有组件
- 验证配置
- 连接数据库

### 2. SNAPSHOT（快照）

```scala
// 1. 记录 Low Watermark
val lowWatermark = getCurrentBinlogPosition()
lowWatermarkManager.recordLowWatermark("snapshot-id", lowWatermark)

// 2. 为每张表创建快照
snapshotManager.startSnapshotBatch(tables, lowWatermark)

// 3. 等待所有快照完成
snapshotManager.waitForSnapshotBatchCompletion(taskIds)
```

**快照过程**：
- 对每张表执行 `SELECT * FROM table`
- 将数据写入目标数据库
- 支持大表分片处理
- 并行处理多张表

### 3. CATCHUP（追赶）

```scala
// 1. 记录 High Watermark
val highWatermark = getCurrentBinlogPosition()

// 2. 为每张表启动 Catchup
catchupManager.startCatchup(tableId, snapshotId, highWatermark)

// 3. 从 Low Watermark 读取 binlog
binlogReader.start(lowWatermark)
  .takeWhile(event => event.position < highWatermark)
  .filter(event => event.tableId == targetTable)
  .runWith(sink)
```

**Catchup 过程**：
- 从 Low Watermark 开始读取 binlog
- 只处理目标表的事件
- 应用到目标数据库
- 直到追平到 High Watermark

### 4. STREAMING（流处理）

```scala
// 从 High Watermark 开始实时处理
binlogReader.start(highWatermark)
  .via(normalizer)
  .via(router)
  .via(applyWorkers)
  .runWith(sink)
```

**流处理过程**：
- 实时读取 binlog 事件
- 标准化和路由
- 并行应用到目标数据库
- 持续运行

## 两种实现方式

### 1. CDCEngine（简化版本）

**特点**：
- 跳过快照和 Catchup 阶段
- 直接进入 Streaming 模式
- 适用于：
  - 新表（没有历史数据）
  - 已经同步的表
  - 测试和开发环境

**使用方式**：
```scala
val engine = CDCEngine(config)
engine.start()
```

### 2. CDCEngineWithSnapshot（完整版本）

**特点**：
- 完整实现快照和 Catchup
- 保证数据一致性
- 适用于：
  - 有历史数据的表
  - 生产环境
  - 需要完整数据同步

**使用方式**：
```scala
val engine = CDCEngineWithSnapshot(config)
engine.start()
```

## 配置示例

```hocon
cdc {
  parallelism {
    # 快照并发数
    snapshotWorkers = 4
    
    # 分区数（影响并行度）
    partitionCount = 8
    
    # 批量大小
    batchSize = 100
  }
  
  snapshot {
    # 快照分片大小
    chunkSize = 10000
    
    # 快照超时
    timeout = 2h
  }
  
  catchup {
    # Catchup 超时
    timeout = 1h
    
    # 批量处理大小
    batchSize = 100
  }
}
```

## 数据一致性保证

### 快照一致性

1. **时间点一致性**：
   - 所有表的快照基于同一个 Low Watermark
   - 保证跨表的一致性视图

2. **事务一致性**：
   - 快照使用 `REPEATABLE READ` 隔离级别
   - 或使用 `START TRANSACTION WITH CONSISTENT SNAPSHOT`

### Catchup 一致性

1. **顺序保证**：
   - 同一主键的事件按顺序处理
   - 使用 hash 分区保证顺序

2. **幂等性**：
   - INSERT 使用 `ON DUPLICATE KEY UPDATE`
   - UPDATE 基于主键，不依赖 before 值
   - DELETE 忽略不存在错误

### 无缝切换

```
Snapshot:  [============================] (Low → High)
Catchup:                                  [=====] (Low → High)
Streaming:                                       [=========>
```

- Catchup 结束位置 = Streaming 开始位置
- 没有数据丢失或重复

## 监控指标

### 快照指标

- `snapshot_total_tasks`：总快照任务数
- `snapshot_completed_tasks`：已完成任务数
- `snapshot_failed_tasks`：失败任务数
- `snapshot_total_rows`：总行数
- `snapshot_duration_ms`：快照耗时

### Catchup 指标

- `catchup_total_tasks`：总 Catchup 任务数
- `catchup_processed_events`：已处理事件数
- `catchup_duration_ms`：Catchup 耗时
- `catchup_lag_ms`：追赶延迟

### 流处理指标

- `streaming_events_per_second`：事件处理速度
- `streaming_lag_ms`：流处理延迟
- `streaming_backpressure`：背压状态

## 故障恢复

### 快照失败

1. **单表失败**：
   - 重试失败的表
   - 其他表继续处理

2. **全局失败**：
   - 清理已完成的快照
   - 重新开始快照流程

### Catchup 失败

1. **重试机制**：
   - 自动重试可恢复错误
   - 指数退避策略

2. **手动恢复**：
   - 检查错误日志
   - 修复数据问题
   - 重新启动 Catchup

### 流处理失败

1. **自动恢复**：
   - 从最后提交的 offset 恢复
   - 重新连接 binlog

2. **数据一致性**：
   - 幂等操作保证重复处理安全
   - 不会丢失或重复数据

## 性能优化

### 快照优化

1. **并行处理**：
   - 多个表并行快照
   - 大表分片处理

2. **批量写入**：
   - 批量 INSERT
   - 使用 prepared statement

3. **资源控制**：
   - 限制并发数
   - 控制内存使用

### Catchup 优化

1. **批量处理**：
   - 批量读取 binlog
   - 批量应用到目标

2. **过滤优化**：
   - 只处理目标表事件
   - 跳过不需要的事件

3. **并行处理**：
   - 多表并行 Catchup
   - 使用分区提高并行度

## 最佳实践

### 1. 选择合适的实现

- **新系统**：使用 CDCEngine（简化版）
- **已有数据**：使用 CDCEngineWithSnapshot（完整版）

### 2. 配置调优

- **小表**：增加并发数，减少分片大小
- **大表**：减少并发数，增加分片大小
- **高负载**：增加批量大小，减少提交频率

### 3. 监控和告警

- 监控快照进度
- 监控 Catchup 延迟
- 设置超时告警

### 4. 测试验证

- 在测试环境验证
- 检查数据一致性
- 测试故障恢复

## 示例代码

### 完整的快照和 Catchup 流程

```scala
import cn.xuyinyin.cdc.engine.CDCEngineWithSnapshot
import cn.xuyinyin.cdc.config.CDCConfig

// 1. 创建配置
val config = CDCConfig(
  source = sourceConfig,
  target = targetConfig,
  filter = filterConfig,
  parallelism = parallelismConfig
)

// 2. 创建引擎
val engine = CDCEngineWithSnapshot(config)

// 3. 启动引擎
engine.start().onComplete {
  case Success(_) =>
    println("CDC Engine started successfully")
    
  case Failure(ex) =>
    println(s"Failed to start CDC Engine: ${ex.getMessage}")
}

// 4. 监控状态
val status = engine.getComponentStatus()
println(s"Current state: ${status("state")}")

// 5. 获取指标
val metrics = engine.getMetrics()
println(s"Snapshot rows: ${metrics.gauges.getOrElse("snapshot_total_rows", 0.0)}")

// 6. 优雅关闭
engine.stop()
```

### 只使用流处理（跳过快照）

```scala
import cn.xuyinyin.cdc.engine.CDCEngine
import cn.xuyinyin.cdc.config.CDCConfig

// 1. 创建配置
val config = CDCConfig(
  source = sourceConfig,
  target = targetConfig,
  filter = filterConfig,
  parallelism = parallelismConfig
)

// 2. 创建引擎（简化版）
val engine = CDCEngine(config)

// 3. 启动引擎（直接进入 Streaming）
engine.start().onComplete {
  case Success(_) =>
    println("CDC Engine started in streaming mode")
    
  case Failure(ex) =>
    println(s"Failed to start CDC Engine: ${ex.getMessage}")
}

// 4. 优雅关闭
engine.stop()
```

## 总结

快照和 Catchup 机制是 CDC 系统的核心功能，确保了：

1. **数据完整性**：全量 + 增量 = 完整数据
2. **一致性保证**：基于 Low/High Watermark 的一致性点
3. **无缝切换**：从快照到流处理的平滑过渡
4. **故障恢复**：支持断点续传和重试

根据实际需求选择合适的实现方式，并进行适当的配置和监控。
