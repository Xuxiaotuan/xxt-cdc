# CDC 系统最终架构总结

## 🎯 核心设计理念

**Connector 是组装者，不是实现者**

- Connector 层负责**组装**各个功能组件
- 功能层负责**实现**具体的业务逻辑
- 两层职责清晰，互不干扰

## 📁 完整目录结构

```
src/main/scala/cn/xuyinyin/cdc/

┌─────────────────────────────────────────────────────────────┐
│                    Connector 层（组装）                      │
└─────────────────────────────────────────────────────────────┘
connector/
├── DataType.scala                    # 通用类型系统
├── SourceConnector.scala             # Source 接口
├── SinkConnector.scala               # Sink 接口
├── ConnectorRegistry.scala           # 注册中心
├── ConnectorBootstrap.scala          # 启动器
├── ConnectorConfig.scala             # 配置
│
├── jdbc/                             # JDBC 公共基类
│   ├── JdbcConnectionManager.scala
│   └── JdbcDataWriter.scala
│
├── source/                           # Source Connectors
│   └── mysql/
│       ├── MySQLSourceConnector.scala    # 组装 Reader + Catalog + Normalizer
│       └── MySQLTypeMapper.scala
│
└── sink/                             # Sink Connectors
    ├── mysql/
    │   ├── MySQLSinkConnector.scala      # 组装 DataWriter
    │   └── MySQLTypeMapper.scala
    └── starrocks/
        ├── StarRocksSinkConnector.scala
        └── StarRocksTypeMapper.scala

┌─────────────────────────────────────────────────────────────┐
│                    功能层（实现）                            │
└─────────────────────────────────────────────────────────────┘
reader/                               # 读取变更日志
├── BinlogReader.scala                # 接口
└── MySQLBinlogReader.scala           # MySQL 实现

catalog/                              # 管理元数据
├── CatalogService.scala              # 接口
└── MySQLCatalogService.scala         # MySQL 实现

normalizer/                           # 标准化事件
├── EventNormalizer.scala             # 接口
└── MySQLEventNormalizer.scala        # MySQL 实现

sink/                                 # 写入数据（旧实现）
├── MySQLSink.scala
├── PooledMySQLSink.scala
└── IdempotentMySQLSink.scala

┌─────────────────────────────────────────────────────────────┐
│                    其他核心组件                              │
└─────────────────────────────────────────────────────────────┘
model/                                # 数据模型
config/                               # 配置管理
pipeline/                             # 流处理管道
worker/                               # 工作器
coordinator/                          # 协调器
router/                               # 路由器
snapshot/                             # 快照管理
metrics/                              # 指标收集
logging/                              # 日志记录
health/                               # 健康检查
filter/                               # 过滤器
error/                                # 错误处理
ddl/                                  # DDL 处理
api/                                  # REST API
engine/                               # CDC 引擎
```

## 🔄 数据流

```
1. 读取
   MySQLBinlogReader (reader/)
   ↓ RawBinlogEvent

2. 标准化
   MySQLEventNormalizer (normalizer/)
   ↓ ChangeEvent

3. 路由
   EventRouter (router/)
   ↓ 分区后的 ChangeEvent

4. 应用
   ApplyWorker (worker/)
   ↓ 调用 DataWriter

5. 写入
   MySQLDataWriter / StarRocksDataWriter (connector/sink/)
   ↓ 目标数据库
```

## 🏗️ Connector 如何工作

### Source Connector（组装 Source 组件）

```scala
class MySQLSourceConnector extends SourceConnector {
  // 组装 Reader（来自 reader/ 包）
  override def createReader(config: DatabaseConfig): BinlogReader = {
    MySQLBinlogReader(config)
  }
  
  // 组装 Catalog（来自 catalog/ 包）
  override def createCatalog(config: DatabaseConfig): CatalogService = {
    MySQLCatalogService(config)
  }
  
  // 组装 Normalizer（来自 normalizer/ 包）
  override def createNormalizer(catalog: CatalogService, db: String): EventNormalizer = {
    MySQLEventNormalizer(catalog, db)
  }
  
  // 提供类型映射
  override def getTypeMapper(): TypeMapper = {
    MySQLTypeMapper()
  }
}
```

### Sink Connector（组装 Sink 组件）

```scala
class MySQLSinkConnector extends SinkConnector {
  // 组装 DataWriter（继承 JdbcDataWriter）
  override def createWriter(config: DatabaseConfig): DataWriter = {
    val connectionManager = JdbcConnectionManager.forMySQL(config)
    new MySQLDataWriter(connectionManager, config.database)
  }
  
  // 提供类型映射
  override def getTypeMapper(): TypeMapper = {
    MySQLTypeMapper()
  }
}
```

### Engine 如何使用 Connector

```scala
class CDCEngine {
  def start(): Future[Done] = {
    // 1. 初始化 Connector
    ConnectorBootstrap.initialize()
    
    // 2. 获取 Connector
    val sourceConnector = ConnectorRegistry.getSource(config.sourceType)
    val sinkConnector = ConnectorRegistry.getSink(config.targetType)
    
    // 3. 创建组件（Connector 负责组装）
    val reader = sourceConnector.createReader(config.source)
    val catalog = sourceConnector.createCatalog(config.source)
    val normalizer = sourceConnector.createNormalizer(catalog, config.source.database)
    val writer = sinkConnector.createWriter(config.target)
    
    // 4. 启动流处理
    pipeline.run()
  }
}
```

## ✨ 核心优势

### 1. 职责清晰

| 层级 | 职责 | 示例 |
|-----|------|------|
| **Connector 层** | 组装和注册 | `MySQLSourceConnector` 组装 Reader + Catalog + Normalizer |
| **功能层** | 具体实现 | `MySQLBinlogReader` 实现 binlog 读取逻辑 |
| **基础层** | 数据模型和工具 | `TableId`, `ChangeEvent`, `CDCMetrics` |

### 2. 高度复用

- ✅ 功能组件可以独立测试
- ✅ 功能组件可以在不同 Connector 中复用
- ✅ 公共逻辑（如 JDBC）可以被多个 Connector 共享

### 3. 易于扩展

添加 PostgreSQL Source 只需：

**步骤 1：实现功能组件**
```
reader/PostgreSQLWALReader.scala
catalog/PostgreSQLCatalogService.scala
normalizer/PostgreSQLEventNormalizer.scala
```

**步骤 2：创建 Connector（组装）**
```
connector/source/postgresql/PostgreSQLSourceConnector.scala
connector/source/postgresql/PostgreSQLTypeMapper.scala
```

**步骤 3：注册**
```scala
ConnectorRegistry.registerSource(PostgreSQLSourceConnector())
```

### 4. 灵活组合

任意 Source 可以与任意 Sink 组合：

| Source | Sink | 状态 |
|--------|------|------|
| MySQL | MySQL | ✅ 已支持 |
| MySQL | StarRocks | ✅ 已支持 |
| PostgreSQL | MySQL | 🔜 只需添加 PostgreSQL Source |
| PostgreSQL | StarRocks | 🔜 只需添加 PostgreSQL Source |
| MySQL | ClickHouse | 🔜 只需添加 ClickHouse Sink |

## 📝 配置示例

### MySQL → MySQL

```hocon
cdc {
  source-type = "mysql"
  target-type = "mysql"
  
  source { ... }
  target { ... }
}
```

### MySQL → StarRocks

```hocon
cdc {
  source-type = "mysql"
  target-type = "starrocks"
  
  source { ... }
  target { ... }
}
```

### PostgreSQL → StarRocks（未来）

```hocon
cdc {
  source-type = "postgresql"
  target-type = "starrocks"
  
  source { ... }
  target { ... }
}
```

## 🎓 设计模式

### 1. 工厂模式

Connector 作为工厂，创建各种组件：

```scala
trait SourceConnector {
  def createReader(...): BinlogReader
  def createCatalog(...): CatalogService
  def createNormalizer(...): EventNormalizer
}
```

### 2. 注册模式

ConnectorRegistry 管理所有 Connector：

```scala
object ConnectorRegistry {
  def registerSource(connector: SourceConnector): Unit
  def getSource(name: String): SourceConnector
}
```

### 3. 策略模式

不同的 Connector 提供不同的实现策略：

```scala
// MySQL 策略
MySQLSourceConnector → MySQLBinlogReader

// PostgreSQL 策略（未来）
PostgreSQLSourceConnector → PostgreSQLWALReader
```

### 4. 适配器模式

TypeMapper 适配不同数据库的类型系统：

```scala
MySQL DECIMAL(10,2) 
  → MySQLTypeMapper → 
DecimalType(10,2) 
  → StarRocksTypeMapper → 
StarRocks DECIMAL64(10,2)
```

## 📚 相关文档

- [架构分层说明](./ARCHITECTURE_LAYERS.md) - 详细的分层架构说明
- [目录结构说明](./CONNECTOR_DIRECTORY_STRUCTURE.md) - 目录组织和命名规范
- [Connector 架构](./CONNECTOR_ARCHITECTURE.md) - Connector 设计和开发指南
- [配置指南](./CONNECTOR_CONFIGURATION.md) - 配置选项和示例
- [重构总结](./CONNECTOR_REFACTORING.md) - 重构过程和成果

## 🎉 总结

这个架构设计的核心思想是：

> **Connector 是组装者，不是实现者**

通过清晰的分层和职责分离，我们实现了：

1. ✅ **清晰的代码组织** - 每个目录都有明确的职责
2. ✅ **高度的可复用性** - 功能组件可以在不同 Connector 中复用
3. ✅ **良好的可扩展性** - 添加新数据库只需实现功能组件，然后组装
4. ✅ **灵活的组合能力** - 任意 Source 可以与任意 Sink 组合

这是一个**合理、优雅、可扩展**的架构设计！🚀
