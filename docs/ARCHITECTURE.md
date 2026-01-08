# 架构设计文档

本文档详细描述 MySQL CDC Service 的架构设计、技术选型和实现细节。

## 目录

- [设计目标](#设计目标)
- [架构概览](#架构概览)
- [核心组件设计](#核心组件设计)
- [数据流设计](#数据流设计)
- [状态管理](#状态管理)
- [性能优化](#性能优化)
- [容错设计](#容错设计)

## 设计目标

### 功能目标
1. **实时数据同步**: 低延迟的 MySQL 到 MySQL 数据同步
2. **数据一致性**: Effectively-once 语义保证
3. **大规模支持**: 支持 70万+ 表的场景
4. **高可用性**: 故障自动恢复，无数据丢失

### 非功能目标
1. **高性能**: 50,000+ TPS 吞吐量
2. **低延迟**: P99 < 1s
3. **可扩展**: 水平扩展能力
4. **可观测**: 完整的监控和告警

## 架构概览

### 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                     Application Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  HTTP API    │  │  Metrics     │  │  Health      │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                      Business Layer                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ CDC Engine   │  │ Hot Set Mgr  │  │ Snapshot Mgr │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                    Stream Processing Layer                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Reader     │→ │  Normalizer  │→ │   Router     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                              │               │
│                                              ▼               │
│                                    ┌──────────────┐          │
│                                    │   Workers    │          │
│                                    └──────────────┘          │
└─────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────┐
│                     Infrastructure Layer                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Offset Store │  │ Catalog Svc  │  │  Sink        │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                        Pekko Actor System                    │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                  Pekko Streams                          │ │
│  │                                                          │ │
│  │  Source ──▶ Flow ──▶ Flow ──▶ Partition ──▶ Sink      │ │
│  │                                                          │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              Typed Actors (Supervision)                 │ │
│  │                                                          │ │
│  │  Engine ──▶ Coordinator ──▶ Workers                    │ │
│  │                                                          │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 核心组件设计

### 1. Binlog Reader

**设计原则**: 单线程顺序读取，保证事件顺序

```scala
class MySQLBinlogReader(
  config: DatabaseConfig,
  offsetStore: OffsetStore
) extends BinlogReader {
  
  private val client = new BinaryLogClient(
    config.host,
    config.port,
    config.username,
    config.password
  )
  
  // 事件监听器
  client.registerEventListener { event =>
    event match {
      case e: WriteRowsEventData => handleInsert(e)
      case e: UpdateRowsEventData => handleUpdate(e)
      case e: DeleteRowsEventData => handleDelete(e)
      case e: QueryEventData => handleDDL(e)
      case _ => // ignore
    }
  }
  
  // 自动重连
  client.setKeepAlive(true)
  client.setKeepAliveInterval(60000)
}
```

**关键特性**:
- 支持 GTID 和 File+Position 模式
- 自动重连机制
- 背压控制
- 心跳检测

### 2. Event Normalizer

**设计原则**: 标准化事件格式，屏蔽底层差异

```scala
trait EventNormalizer {
  def normalize(rawEvent: RawBinlogEvent): Future[ChangeEvent]
}

case class ChangeEvent(
  tableId: TableId,
  operation: Operation,  // INSERT, UPDATE, DELETE
  primaryKey: Map[String, Any],
  before: Option[Map[String, Any]],
  after: Option[Map[String, Any]],
  timestamp: Instant,
  position: BinlogPosition
)
```

**处理流程**:
1. 解析 Binlog 事件
2. 提取表信息和主键
3. 转换数据类型
4. 构建标准化事件

### 3. Hot Set Manager

**设计原则**: 动态管理活跃表，优化资源使用

```scala
class DefaultHotSetManager(config: HotSetConfig) {
  
  // 表温度追踪
  private val temperatures = ConcurrentHashMap[TableId, Temperature]()
  
  // 热表集
  private val hotSet = ConcurrentHashMap[TableId, HotTableEntry]()
  
  def recordEvent(tableId: TableId): Unit = {
    // 更新温度
    val temp = temperatures.computeIfAbsent(tableId, _ => Temperature.zero)
    temp.increment()
    
    // 检查是否应该加入热表集
    if (temp.value > config.threshold && !hotSet.contains(tableId)) {
      promoteToHotSet(tableId)
    }
  }
  
  def cooldown(): Unit = {
    val now = Instant.now()
    hotSet.forEach { (tableId, entry) =>
      if (entry.lastAccess.isBefore(now.minus(config.cooldownTime))) {
        demoteFromHotSet(tableId)
      }
    }
  }
}
```

**温度计算算法**:
```
temperature = event_count / time_window

升温条件: temperature > threshold
降温条件: no_events_for > cooldown_time
```

### 4. Event Router

**设计原则**: 一致性哈希，保证顺序性

```scala
class HashBasedRouter(partitionCount: Int) extends EventRouter {
  
  def route(event: ChangeEvent): Int = {
    val key = s"${event.tableId}:${event.primaryKey}"
    val hash = MurmurHash3.stringHash(key)
    Math.abs(hash % partitionCount)
  }
}
```

**路由保证**:
- 同表同主键 → 同一分区
- 不同表 → 均匀分布
- 分区数可配置

### 5. Apply Workers

**设计原则**: 并行处理，批量写入

```scala
class DefaultApplyWorker(
  workerId: Int,
  sink: MySQLSink,
  offsetCoordinator: OffsetCoordinator,
  config: WorkerConfig
) extends ApplyWorker {
  
  private val buffer = mutable.ListBuffer[ChangeEvent]()
  
  def process(event: ChangeEvent): Future[Unit] = {
    buffer += event
    
    if (buffer.size >= config.batchSize || shouldFlush()) {
      flush()
    } else {
      Future.successful(())
    }
  }
  
  private def flush(): Future[Unit] = {
    val batch = buffer.toList
    buffer.clear()
    
    for {
      _ <- sink.writeBatch(batch)
      _ <- offsetCoordinator.markApplied(batch)
    } yield ()
  }
}
```

**批处理策略**:
- 批量大小触发
- 时间间隔触发
- 内存压力触发

### 6. Offset Coordinator

**设计原则**: 三阶段状态机，保证一致性

```scala
class DefaultOffsetCoordinator extends OffsetCoordinator {
  
  // 分区状态
  private val partitionStates = ConcurrentHashMap[Int, PartitionState]()
  
  def markReceived(partition: Int, position: BinlogPosition): Unit = {
    partitionStates.compute(partition, (_, state) =>
      state.copy(
        receivedPosition = position,
        status = OffsetStatus.RECEIVED
      )
    )
  }
  
  def markApplied(partition: Int, position: BinlogPosition): Unit = {
    partitionStates.compute(partition, (_, state) =>
      state.copy(
        appliedPosition = position,
        status = OffsetStatus.APPLIED
      )
    )
  }
  
  def getCommittablePosition(): Option[BinlogPosition] = {
    // 返回所有分区中最小的已应用位置
    val positions = partitionStates.values()
      .filter(_.status == OffsetStatus.APPLIED)
      .map(_.appliedPosition)
    
    if (positions.isEmpty) None
    else Some(positions.min)
  }
}
```

**状态转换**:
```
RECEIVED → APPLIED → COMMITTED

只有所有分区都达到 APPLIED 状态，
才能提交全局偏移量。
```

### 7. Idempotent Sink

**设计原则**: 幂等写入，支持重试

```scala
class IdempotentMySQLSink(connectionPool: HikariDataSource) {
  
  def writeInsert(event: ChangeEvent): Future[Unit] = {
    val sql = s"""
      INSERT INTO ${event.tableId} (${columns})
      VALUES (${placeholders})
      ON DUPLICATE KEY UPDATE ${updateClause}
    """
    executeUpdate(sql, event.after.get)
  }
  
  def writeUpdate(event: ChangeEvent): Future[Unit] = {
    val sql = s"""
      UPDATE ${event.tableId}
      SET ${setClause}
      WHERE ${primaryKeyClause}
    """
    executeUpdate(sql, event.after.get)
  }
  
  def writeDelete(event: ChangeEvent): Future[Unit] = {
    val sql = s"""
      DELETE FROM ${event.tableId}
      WHERE ${primaryKeyClause}
    """
    // 忽略不存在错误
    executeUpdate(sql, event.primaryKey).recover {
      case _: SQLException => () // ignore
    }
  }
}
```

## 数据流设计

### Pekko Streams 管道

```scala
class CDCStreamPipeline(
  reader: BinlogReader,
  normalizer: EventNormalizer,
  hotSetManager: HotSetManager,
  router: EventRouter,
  workers: Seq[ApplyWorker],
  sink: MySQLSink
) {
  
  def build(): RunnableGraph[Future[Done]] = {
    Source.fromPublisher(reader.eventPublisher)
      .mapAsync(1)(normalizer.normalize)
      .filter(event => hotSetManager.isHot(event.tableId))
      .map(event => (router.route(event), event))
      .groupBy(workers.size, _._1)
      .mapAsync(1) { case (partition, event) =>
        workers(partition).process(event)
      }
      .mergeSubstreams
      .to(Sink.ignore)
  }
}
```

### 背压控制

```
Reader (slow) ←─ backpressure ─← Normalizer ←─ backpressure ─← Workers

当下游处理慢时，自动减慢上游读取速度
```

## 状态管理

### CDC 状态机

```
INIT → SNAPSHOT → CATCHUP → STREAMING → STOPPED

INIT:      初始化，加载配置
SNAPSHOT:  全量快照阶段
CATCHUP:   增量追赶阶段
STREAMING: 实时同步阶段
STOPPED:   停止状态
```

### 偏移量状态机

```
RECEIVED → APPLIED → COMMITTED

RECEIVED:  事件已接收
APPLIED:   事件已应用到目标库
COMMITTED: 偏移量已持久化
```

## 性能优化

### 1. 批量处理

```scala
// 批量写入
val batch = events.grouped(batchSize)
batch.foreach { events =>
  connection.setAutoCommit(false)
  events.foreach(applyEvent)
  connection.commit()
}
```

### 2. 连接池优化

```scala
val config = new HikariConfig()
config.setMaximumPoolSize(50)
config.setMinimumIdle(10)
config.setConnectionTimeout(30000)
config.addDataSourceProperty("cachePrepStmts", "true")
config.addDataSourceProperty("prepStmtCacheSize", "250")
```

### 3. 并行处理

```scala
// 多个 worker 并行处理不同分区
val workers = (0 until workerCount).map { id =>
  new ApplyWorker(id, sink, coordinator)
}
```

### 4. 内存优化

```scala
// 限制热表集大小
if (hotSet.size > maxSize) {
  evictColdestTable()
}

// 限制批处理大小
if (buffer.size > maxBatchSize) {
  flush()
}
```

## 容错设计

### 1. 故障检测

```scala
// 心跳检测
scheduler.scheduleAtFixedRate(
  initialDelay = 30.seconds,
  interval = 30.seconds
) {
  if (!reader.isConnected) {
    reconnect()
  }
}
```

### 2. 自动重试

```scala
def executeWithRetry[T](
  operation: () => Future[T],
  maxRetries: Int = 3
): Future[T] = {
  operation().recoverWith {
    case ex: RetryableException if retries < maxRetries =>
      Thread.sleep(backoffDelay(retries))
      executeWithRetry(operation, maxRetries, retries + 1)
  }
}
```

### 3. 断路器

```scala
val circuitBreaker = new CircuitBreaker(
  failureThreshold = 5,
  timeout = 60.seconds
)

circuitBreaker.execute {
  sink.write(event)
}
```

### 4. 优雅关闭

```scala
def shutdown(): Future[Unit] = {
  for {
    _ <- stopReader()
    _ <- flushAllWorkers()
    _ <- commitOffsets()
    _ <- closeConnections()
  } yield ()
}
```

## 监控设计

### 指标收集

```scala
class MetricsCollector {
  val ingestCounter = Counter.build()
    .name("cdc_events_ingested_total")
    .help("Total events ingested")
    .register()
  
  val lagGauge = Gauge.build()
    .name("cdc_binlog_lag_seconds")
    .help("Binlog lag in seconds")
    .register()
}
```

### 健康检查

```scala
def healthCheck(): HealthStatus = {
  val checks = Seq(
    checkDatabaseConnection(),
    checkBinlogReader(),
    checkWorkers(),
    checkOffsetStore()
  )
  
  if (checks.forall(_.isHealthy)) {
    HealthStatus.Healthy
  } else {
    HealthStatus.Unhealthy(checks.filter(!_.isHealthy))
  }
}
```

## 扩展性设计

### 水平扩展

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ CDC Instance│     │ CDC Instance│     │ CDC Instance│
│   (Table A) │     │   (Table B) │     │   (Table C) │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │                   │
       └───────────────────┴───────────────────┘
                           │
                    ┌──────────────┐
                    │ Target MySQL │
                    └──────────────┘
```

### 垂直扩展

```
增加资源配置:
- CPU: 2 → 8 核
- Memory: 2GB → 8GB
- Workers: 4 → 16
- Partitions: 16 → 64
```

## 安全设计

### 1. 认证授权

```sql
-- 最小权限原则
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT 
ON *.* TO 'cdc_user'@'%';
```

### 2. 数据加密

```scala
// SSL 连接
config.addDataSourceProperty("useSSL", "true")
config.addDataSourceProperty("requireSSL", "true")
```

### 3. 敏感信息保护

```scala
// 配置文件中的密码加密
password = ${?CDC_PASSWORD}  // 从环境变量读取
```

## 总结

MySQL CDC Service 采用了模块化、分层的架构设计，通过以下关键技术实现了高性能、高可用的数据同步：

1. **单线程读取 + 多线程处理**: 保证顺序性的同时提高吞吐量
2. **热表集管理**: 优化大规模场景下的资源使用
3. **三阶段状态机**: 保证 Effectively-once 语义
4. **幂等写入**: 支持重试和故障恢复
5. **Pekko Streams**: 提供背压控制和流式处理能力

这些设计使得系统能够在保证数据一致性的前提下，实现高性能、低延迟的数据同步。