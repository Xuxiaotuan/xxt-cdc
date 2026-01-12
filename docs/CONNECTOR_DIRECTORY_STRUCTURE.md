# Connector 目录结构说明

## 完整的目录结构

CDC 系统采用清晰的分层架构，将 Connector（组装层）与具体实现（功能层）分离：

```
src/main/scala/cn/xuyinyin/cdc/

# ============ Connector 层（组装和注册）============
connector/
├── DataType.scala                    # 通用类型系统
├── SourceConnector.scala             # Source Connector 接口
├── SinkConnector.scala               # Sink Connector 接口
├── ConnectorConfig.scala             # Connector 配置
├── ConnectorRegistry.scala           # Connector 注册中心
├── ConnectorBootstrap.scala          # Connector 启动器
│
├── jdbc/                             # JDBC 公共组件
│   ├── JdbcConnectionManager.scala   # 连接管理器
│   └── JdbcDataWriter.scala          # JDBC 写入器基类
│
├── source/                           # Source Connectors（组装）
│   └── mysql/
│       ├── MySQLSourceConnector.scala  # 组装 Reader + Catalog + Normalizer
│       └── MySQLTypeMapper.scala
│
└── sink/                             # Sink Connectors（组装）
    ├── mysql/
    │   ├── MySQLSinkConnector.scala    # 组装 DataWriter
    │   └── MySQLTypeMapper.scala
    └── starrocks/
        ├── StarRocksSinkConnector.scala
        └── StarRocksTypeMapper.scala

# ============ 功能层（具体实现）============
reader/                               # 变更日志读取
├── BinlogReader.scala                # 通用接口
└── MySQLBinlogReader.scala           # MySQL 实现

catalog/                              # 元数据管理
├── CatalogService.scala              # 通用接口
└── MySQLCatalogService.scala         # MySQL 实现

normalizer/                           # 事件标准化
├── EventNormalizer.scala             # 通用接口
└── MySQLEventNormalizer.scala        # MySQL 实现

sink/                                 # 数据写入（旧实现，保留兼容）
├── MySQLSink.scala                   # MySQL Sink 接口
├── PooledMySQLSink.scala             # 带连接池的实现
└── IdempotentMySQLSink.scala         # 幂等实现

# ============ 其他核心组件 ============
model/                                # 数据模型
config/                               # 配置管理
pipeline/                             # 流处理管道
worker/                               # 工作器
coordinator/                          # 协调器
...
```

## 设计原则

### 1. 清晰的分层架构

**Connector 层（组装层）**：
- 职责：组装和注册各个功能组件
- 位置：`connector/source/` 和 `connector/sink/`
- 示例：`MySQLSourceConnector` 组装 `MySQLBinlogReader` + `MySQLCatalogService` + `MySQLEventNormalizer`

**功能层（实现层）**：
- 职责：提供具体的功能实现
- 位置：`reader/`、`catalog/`、`normalizer/`、`sink/` 等
- 示例：`MySQLBinlogReader` 实现 `BinlogReader` 接口

**优势**：
- ✅ 接口和实现分离
- ✅ Connector 只负责组装，不重复实现
- ✅ 功能组件可以独立测试和复用
- ✅ 添加新数据库时，只需实现功能组件，然后用 Connector 组装

### 2. Source 和 Sink 独立

Source 和 Sink 可以独立实现，互不影响：

- **MySQL Source** + **MySQL Sink** = MySQL → MySQL 同步
- **MySQL Source** + **StarRocks Sink** = MySQL → StarRocks 同步
- **PostgreSQL Source** + **StarRocks Sink** = PostgreSQL → StarRocks 同步（未来）

### 3. 类型映射器独立

每个 Source 和 Sink 都有自己的 TypeMapper：

- `connector/source/mysql/MySQLTypeMapper` - 用于读取 MySQL 数据时的类型映射
- `connector/sink/mysql/MySQLTypeMapper` - 用于写入 MySQL 数据时的类型映射
- `connector/sink/starrocks/StarRocksTypeMapper` - 用于写入 StarRocks 数据时的类型映射

虽然 MySQL Source 和 MySQL Sink 的 TypeMapper 实现相同，但保持独立可以：
- 未来根据需要独立优化
- 避免 Source 和 Sink 之间的耦合
- 更清晰的职责分离

### 4. 公共组件复用

JDBC 相关的公共逻辑放在 `connector/jdbc/` 目录下：

- `JdbcConnectionManager` - 统一的连接池管理
- `JdbcDataWriter` - JDBC 写入器基类

所有基于 JDBC 的 Sink Connector 都可以继承这些基类。

## 添加新的 Connector

### 添加新的 Source Connector

例如添加 PostgreSQL Source：

**步骤 1：实现功能组件**
```
src/main/scala/cn/xuyinyin/cdc/
├── reader/
│   └── PostgreSQLWALReader.scala          # 实现 BinlogReader 接口
├── catalog/
│   └── PostgreSQLCatalogService.scala     # 实现 CatalogService 接口
└── normalizer/
    └── PostgreSQLEventNormalizer.scala    # 实现 EventNormalizer 接口
```

**步骤 2：创建 Connector（组装）**
```
src/main/scala/cn/xuyinyin/cdc/connector/source/postgresql/
├── PostgreSQLSourceConnector.scala        # 组装上述组件
└── PostgreSQLTypeMapper.scala
```

**PostgreSQLSourceConnector 示例**：
```scala
class PostgreSQLSourceConnector extends SourceConnector {
  override def createReader(config: DatabaseConfig): BinlogReader = {
    PostgreSQLWALReader(config)  // 使用 reader/ 下的实现
  }
  
  override def createCatalog(config: DatabaseConfig): CatalogService = {
    PostgreSQLCatalogService(config)  // 使用 catalog/ 下的实现
  }
  
  override def createNormalizer(catalog: CatalogService, db: String): EventNormalizer = {
    PostgreSQLEventNormalizer(catalog, db)  // 使用 normalizer/ 下的实现
  }
}
```

### 添加新的 Sink Connector

例如添加 ClickHouse Sink：

**步骤 1：创建 Connector 和 Writer**
```
src/main/scala/cn/xuyinyin/cdc/connector/sink/clickhouse/
├── ClickHouseSinkConnector.scala
├── ClickHouseTypeMapper.scala
└── ClickHouseDataWriter.scala         # 继承 JdbcDataWriter
```

**ClickHouseSinkConnector 示例**：
```scala
class ClickHouseSinkConnector extends SinkConnector {
  override def createWriter(config: DatabaseConfig): DataWriter = {
    val connectionManager = JdbcConnectionManager.forClickHouse(config)
    new ClickHouseDataWriter(connectionManager, config.database)
  }
}
```

## 包命名规范

### Source Connector

```scala
package cn.xuyinyin.cdc.connector.source.{database}

// 例如：
package cn.xuyinyin.cdc.connector.source.mysql
package cn.xuyinyin.cdc.connector.source.postgresql
```

### Sink Connector

```scala
package cn.xuyinyin.cdc.connector.sink.{database}

// 例如：
package cn.xuyinyin.cdc.connector.sink.mysql
package cn.xuyinyin.cdc.connector.sink.starrocks
package cn.xuyinyin.cdc.connector.sink.clickhouse
```

## 注册 Connector

在 `ConnectorBootstrap.scala` 中注册新的 Connector：

```scala
import cn.xuyinyin.cdc.connector.source.mysql.{MySQLSourceConnector => MySQLSource}
import cn.xuyinyin.cdc.connector.sink.mysql.{MySQLSinkConnector => MySQLSink}
import cn.xuyinyin.cdc.connector.sink.starrocks.{StarRocksSinkConnector => StarRocksSink}

def initialize(): Unit = {
  // 注册 MySQL
  ConnectorRegistry.registerSource(MySQLSource())
  ConnectorRegistry.registerSink(MySQLSink())
  
  // 注册 StarRocks
  ConnectorRegistry.registerSink(StarRocksSink())
  
  // 未来添加更多...
}
```

## 配置示例

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

## 优势

### 1. 清晰的职责分离

- `source/` - 只负责读取数据
- `sink/` - 只负责写入数据
- `jdbc/` - 公共的 JDBC 逻辑

### 2. 灵活的组合

任意 Source 可以与任意 Sink 组合：

| Source | Sink | 支持状态 |
|--------|------|---------|
| MySQL | MySQL | ✅ 已支持 |
| MySQL | StarRocks | ✅ 已支持 |
| PostgreSQL | MySQL | 🔜 计划中 |
| PostgreSQL | StarRocks | 🔜 计划中 |
| MySQL | ClickHouse | 🔜 计划中 |

### 3. 易于扩展

添加新数据库支持只需：
1. 在 `source/` 或 `sink/` 下创建新目录
2. 实现相应的 Connector 接口
3. 在 `ConnectorBootstrap` 中注册

### 4. 独立维护

每个 Connector 可以独立开发和维护：
- MySQL Source 的改动不影响 StarRocks Sink
- 可以为不同 Connector 设置不同的维护者
- 更容易进行单元测试

## 迁移指南

### 从旧结构迁移

**旧结构**：
```
connector/
├── mysql/
│   ├── MySQLSourceConnector.scala
│   ├── MySQLSinkConnector.scala
│   └── MySQLTypeMapper.scala
└── starrocks/
    ├── StarRocksSinkConnector.scala
    └── StarRocksTypeMapper.scala
```

**新结构**：
```
connector/
├── source/
│   └── mysql/
│       ├── MySQLSourceConnector.scala
│       └── MySQLTypeMapper.scala
└── sink/
    ├── mysql/
    │   ├── MySQLSinkConnector.scala
    │   └── MySQLTypeMapper.scala
    └── starrocks/
        ├── StarRocksSinkConnector.scala
        └── StarRocksTypeMapper.scala
```

### Import 路径变化

**旧的 import**：
```scala
import cn.xuyinyin.cdc.connector.mysql.MySQLSourceConnector
import cn.xuyinyin.cdc.connector.mysql.MySQLSinkConnector
import cn.xuyinyin.cdc.connector.starrocks.StarRocksSinkConnector
```

**新的 import**：
```scala
import cn.xuyinyin.cdc.connector.source.mysql.MySQLSourceConnector
import cn.xuyinyin.cdc.connector.sink.mysql.MySQLSinkConnector
import cn.xuyinyin.cdc.connector.sink.starrocks.StarRocksSinkConnector
```

## 总结

新的目录结构提供了：
- ✅ 更清晰的代码组织
- ✅ Source 和 Sink 完全独立
- ✅ 更容易添加新的数据库支持
- ✅ 更好的可维护性
- ✅ 灵活的数据库组合

这为未来支持更多数据库（PostgreSQL、ClickHouse、Doris、Kafka 等）打下了坚实的基础。
