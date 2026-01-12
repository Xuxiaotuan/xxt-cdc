# CDC 系统架构分层说明

## 架构概览

CDC 系统采用清晰的分层架构，将职责明确分离：

```
┌─────────────────────────────────────────────────────────────┐
│                     应用层 (Application)                     │
│                    CDCEngine, CDCApplication                 │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                   Connector 层 (Assembly)                    │
│              组装和注册各个功能组件                          │
│  - SourceConnector: 组装 Reader + Catalog + Normalizer      │
│  - SinkConnector: 组装 DataWriter                           │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                   功能层 (Implementation)                    │
│              提供具体的功能实现                              │
│  - Reader: 读取变更日志                                     │
│  - Catalog: 管理元数据                                      │
│  - Normalizer: 标准化事件                                   │
│  - Sink: 写入数据                                           │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                   基础层 (Foundation)                        │
│  - Model: 数据模型                                          │
│  - Config: 配置管理                                         │
│  - Metrics: 指标收集                                        │
└─────────────────────────────────────────────────────────────┘
```

## 各层职责

### 1. 应用层 (Application Layer)

**位置**：`engine/`, `api/`, `CDCApplication.scala`

**职责**：
- 启动和管理 CDC 引擎
- 提供 REST API
- 处理生命周期管理

**示例**：
```scala
class CDCEngine {
  def start(): Future[Done] = {
    // 1. 初始化 Connector
    ConnectorBootstrap.initialize()
    
    // 2. 获取 Source Connector
    val sourceConnector = ConnectorRegistry.getSource(config.sourceType)
    
    // 3. 创建组件
    val reader = sourceConnector.createReader(config.source)
    val catalog = sourceConnector.createCatalog(config.source)
    val normalizer = sourceConnector.createNormalizer(catalog, config.source.database)
    
    // 4. 启动流处理
    pipeline.run()
  }
}
```

### 2. Connector 层 (Assembly Layer)

**位置**：`connector/`

**职责**：
- 组装功能组件
- 注册和发现 Connector
- 提供类型映射

**关键组件**：

#### SourceConnector（组装 Source 组件）
```scala
trait SourceConnector {
  def createReader(config: DatabaseConfig): BinlogReader
  def createCatalog(config: DatabaseConfig): CatalogService
  def createNormalizer(catalog: CatalogService, db: String): EventNormalizer
  def getTypeMapper(): TypeMapper
}
```

#### SinkConnector（组装 Sink 组件）
```scala
trait SinkConnector {
  def createWriter(config: DatabaseConfig): DataWriter
  def getTypeMapper(): TypeMapper
}
```

#### ConnectorRegistry（注册和发现）
```scala
object ConnectorRegistry {
  def registerSource(connector: SourceConnector): Unit
  def registerSink(connector: SinkConnector): Unit
  def getSource(name: String): SourceConnector
  def getSink(name: String): SinkConnector
}
```

**示例**：
```scala
// MySQL Source Connector 组装 MySQL 的各个组件
class MySQLSourceConnector extends SourceConnector {
  override def createReader(config: DatabaseConfig): BinlogReader = {
    MySQLBinlogReader(config)  // 来自 reader/ 包
  }
  
  override def createCatalog(config: DatabaseConfig): CatalogService = {
    MySQLCatalogService(config)  // 来自 catalog/ 包
  }
  
  override def createNormalizer(catalog: CatalogService, db: String): EventNormalizer = {
    MySQLEventNormalizer(catalog, db)  // 来自 normalizer/ 包
  }
}
```

### 3. 功能层 (Implementation Layer)

**位置**：`reader/`, `catalog/`, `normalizer/`, `sink/`, `worker/`, `pipeline/` 等

**职责**：
- 提供具体的功能实现
- 实现通用接口
- 可以独立测试和复用

#### Reader（读取变更日志）

**位置**：`reader/`

**接口**：
```scala
trait BinlogReader {
  def start(startPosition: BinlogPosition): Source[RawBinlogEvent, NotUsed]
  def getCurrentPosition(): BinlogPosition
  def stop(): Unit
}
```

**实现**：
- `MySQLBinlogReader` - 读取 MySQL binlog
- `PostgreSQLWALReader` - 读取 PostgreSQL WAL（未来）

#### Catalog（管理元数据）

**位置**：`catalog/`

**接口**：
```scala
trait CatalogService {
  def discoverTables(filter: FilterConfig): Future[Seq[TableMeta]]
  def getTableSchema(table: TableId): Future[TableSchema]
  def validateBinlogConfig(config: DatabaseConfig): Future[BinlogCapability]
}
```

**实现**：
- `MySQLCatalogService` - MySQL 元数据管理
- `PostgreSQLCatalogService` - PostgreSQL 元数据管理（未来）

#### Normalizer（标准化事件）

**位置**：`normalizer/`

**接口**：
```scala
trait EventNormalizer {
  def normalize(rawEvent: RawBinlogEvent): Option[ChangeEvent]
}
```

**实现**：
- `MySQLEventNormalizer` - 标准化 MySQL binlog 事件
- `PostgreSQLEventNormalizer` - 标准化 PostgreSQL WAL 事件（未来）

#### Sink（写入数据）

**位置**：`sink/`（旧实现）、`connector/jdbc/`（新实现）

**旧接口**（保留兼容）：
```scala
trait MySQLSink {
  def executeInsert(table: TableId, data: Map[String, Any]): Future[Unit]
  def executeUpdate(table: TableId, pk: Map[String, Any], data: Map[String, Any]): Future[Unit]
  def executeDelete(table: TableId, pk: Map[String, Any]): Future[Unit]
}
```

**新接口**（通用）：
```scala
trait DataWriter {
  def insert(table: TableId, data: Map[String, Any]): Future[Unit]
  def update(table: TableId, pk: Map[String, Any], data: Map[String, Any]): Future[Unit]
  def delete(table: TableId, pk: Map[String, Any]): Future[Unit]
  def batchInsert(table: TableId, rows: Seq[Map[String, Any]]): Future[Unit]
  def close(): Unit
}
```

**实现**：
- `PooledMySQLSink` - 旧的 MySQL Sink 实现
- `JdbcDataWriter` - 新的 JDBC 写入器基类
- `MySQLDataWriter` - 继承 `JdbcDataWriter`
- `StarRocksDataWriter` - 继承 `JdbcDataWriter`

### 4. 基础层 (Foundation Layer)

**位置**：`model/`, `config/`, `metrics/`, `logging/` 等

**职责**：
- 提供基础数据结构
- 配置管理
- 指标收集
- 日志记录

**关键组件**：
- `model/` - 数据模型（`TableId`, `ChangeEvent`, `BinlogPosition` 等）
- `config/` - 配置管理（`CDCConfig`, `DatabaseConfig` 等）
- `metrics/` - 指标收集（`CDCMetrics`, `MetricsSnapshot` 等）
- `logging/` - 日志记录（`CDCLogging`, `PerformanceLogger` 等）

## 数据流

```
1. 读取阶段
   MySQLBinlogReader (reader/)
   ↓ RawBinlogEvent
   
2. 标准化阶段
   MySQLEventNormalizer (normalizer/)
   ↓ ChangeEvent
   
3. 路由阶段
   EventRouter (router/)
   ↓ 分区后的 ChangeEvent
   
4. 应用阶段
   ApplyWorker (worker/)
   ↓ 调用 DataWriter
   
5. 写入阶段
   MySQLDataWriter / StarRocksDataWriter (connector/sink/)
   ↓ 写入目标数据库
```

## 添加新数据库的步骤

### 添加新的 Source（如 PostgreSQL）

1. **实现功能组件**：
   ```
   reader/PostgreSQLWALReader.scala
   catalog/PostgreSQLCatalogService.scala
   normalizer/PostgreSQLEventNormalizer.scala
   ```

2. **创建 Connector**：
   ```
   connector/source/postgresql/PostgreSQLSourceConnector.scala
   connector/source/postgresql/PostgreSQLTypeMapper.scala
   ```

3. **注册 Connector**：
   ```scala
   ConnectorRegistry.registerSource(PostgreSQLSourceConnector())
   ```

### 添加新的 Sink（如 ClickHouse）

1. **创建 Connector 和 Writer**：
   ```
   connector/sink/clickhouse/ClickHouseSinkConnector.scala
   connector/sink/clickhouse/ClickHouseDataWriter.scala
   connector/sink/clickhouse/ClickHouseTypeMapper.scala
   ```

2. **注册 Connector**：
   ```scala
   ConnectorRegistry.registerSink(ClickHouseSinkConnector())
   ```

## 优势

### 1. 清晰的职责分离

- **Connector 层**：只负责组装，不实现具体逻辑
- **功能层**：只负责实现，不关心如何被组装
- **基础层**：提供通用的数据结构和工具

### 2. 高度可复用

- 功能组件可以独立测试
- 功能组件可以在不同 Connector 中复用
- 公共逻辑（如 JDBC）可以被多个 Connector 共享

### 3. 易于扩展

- 添加新数据库只需实现功能组件，然后用 Connector 组装
- 不需要修改现有代码
- 新旧实现可以并存（如 `MySQLSink` 和 `DataWriter`）

### 4. 灵活组合

- 任意 Source 可以与任意 Sink 组合
- MySQL → MySQL
- MySQL → StarRocks
- PostgreSQL → StarRocks
- 等等...

## 总结

这种分层架构提供了：
- ✅ 清晰的职责分离
- ✅ 高度的可复用性
- ✅ 良好的可扩展性
- ✅ 灵活的组合能力

Connector 层作为"组装者"，将功能层的各个组件组装成完整的 CDC 解决方案，而不是重复实现这些功能。这是一个非常合理和优雅的设计！
