# CDC Connector 插件化架构

## 概述

CDC 系统采用插件化的 Connector 架构，将数据源和目标抽象为可插拔的组件。这使得系统可以轻松支持多种数据库类型，而无需修改核心引擎代码。

## 架构设计

### 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                        CDC Engine                            │
│  (与具体数据库解耦，只依赖抽象接口)                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Connector Framework                        │
│  - SourceConnector (Reader + Catalog + Normalizer)          │
│  - SinkConnector (Writer + Type Mapper)                     │
│  - TypeSystem (通用类型 + 类型映射)                         │
│  - ConnectorRegistry (注册和发现)                           │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│MySQL         │      │PostgreSQL    │      │StarRocks     │
│Connector     │      │Connector     │      │Connector     │
│              │      │              │      │              │
│- Reader      │      │- Reader      │      │- Writer      │
│- Catalog     │      │- Catalog     │      │- TypeMapper  │
│- Normalizer  │      │- Normalizer  │      │              │
│- Sink        │      │- TypeMapper  │      │              │
└──────────────┘      └──────────────┘      └──────────────┘
```

### 关键接口

#### 1. SourceConnector

负责从源数据库读取 CDC 事件：

```scala
trait SourceConnector {
  def name: String
  def version: String
  def createReader(config: DatabaseConfig): BinlogReader
  def createCatalog(config: DatabaseConfig): CatalogService
  def createNormalizer(catalog: CatalogService, sourceDatabase: String): EventNormalizer
  def getTypeMapper(): TypeMapper
}
```

#### 2. SinkConnector

负责向目标数据库写入数据：

```scala
trait SinkConnector {
  def name: String
  def version: String
  def createWriter(config: DatabaseConfig): DataWriter
  def getTypeMapper(): TypeMapper
}
```

#### 3. DataWriter

统一的数据写入接口：

```scala
trait DataWriter {
  def insert(table: TableId, data: Map[String, Any]): Future[Unit]
  def update(table: TableId, primaryKey: Map[String, Any], data: Map[String, Any]): Future[Unit]
  def delete(table: TableId, primaryKey: Map[String, Any]): Future[Unit]
  def batchInsert(table: TableId, rows: Seq[Map[String, Any]]): Future[Unit]
  def close(): Unit
}
```

#### 4. TypeMapper

类型映射器，负责类型转换：

```scala
trait TypeMapper {
  def toGenericType(nativeType: String): DataType
  def toNativeType(genericType: DataType): String
  def convertValue(value: Any, sourceType: DataType, targetType: DataType): Any
}
```

## 已支持的 Connector

### MySQL Source Connector

- **名称**: `mysql`
- **版本**: `1.0.0`
- **功能**:
  - ✅ 增量同步（Binlog）
  - ✅ 全量快照
  - ✅ DDL 捕获
  - ✅ GTID 支持
  - ✅ 并行快照

### MySQL Sink Connector

- **名称**: `mysql`
- **版本**: `1.0.0`
- **功能**:
  - ✅ 幂等写入
  - ✅ 批量写入
  - ✅ 事务支持
  - ✅ Upsert 支持
  - ✅ DDL 同步

### StarRocks Sink Connector

- **名称**: `starrocks`
- **版本**: `1.0.0`
- **功能**:
  - ✅ 幂等写入（基于 Primary Key 表）
  - ✅ 批量写入
  - ✅ Upsert 支持
  - ⚠️ 不支持传统事务
  - ⚠️ 不支持 DDL 同步

## 配置示例

### MySQL → MySQL

```hocon
cdc {
  task-name = "mysql-to-mysql"
  source-type = "mysql"  # 源数据库类型
  target-type = "mysql"  # 目标数据库类型
  
  source {
    host = "source-mysql"
    port = 3306
    username = "root"
    password = "password"
    database = "source_db"
  }
  
  target {
    host = "target-mysql"
    port = 3306
    username = "root"
    password = "password"
    database = "target_db"
  }
}
```

### MySQL → StarRocks

```hocon
cdc {
  task-name = "mysql-to-starrocks"
  source-type = "mysql"
  target-type = "starrocks"
  
  source {
    host = "mysql-host"
    port = 3306
    username = "root"
    password = "password"
    database = "source_db"
  }
  
  target {
    host = "starrocks-fe"
    port = 9030
    username = "root"
    password = "password"
    database = "target_db"
  }
}
```

## 如何添加新的 Connector

### 步骤 1: 创建 TypeMapper

```scala
package cn.xuyinyin.cdc.connector.postgresql

class PostgreSQLTypeMapper extends TypeMapper {
  override def toGenericType(nativeType: String): DataType = {
    // 实现 PostgreSQL 类型到通用类型的映射
    nativeType.toUpperCase match {
      case "INTEGER" => IntType
      case "BIGINT" => BigIntType
      case "VARCHAR" => VarCharType(255)
      // ... 更多类型映射
    }
  }
  
  override def toNativeType(genericType: DataType): String = {
    // 实现通用类型到 PostgreSQL 类型的映射
    genericType match {
      case IntType => "INTEGER"
      case BigIntType => "BIGINT"
      case VarCharType(len) => s"VARCHAR($len)"
      // ... 更多类型映射
    }
  }
}
```

### 步骤 2: 实现 SourceConnector（如果是数据源）

```scala
package cn.xuyinyin.cdc.connector.postgresql

class PostgreSQLSourceConnector extends SourceConnector {
  override def name: String = "postgresql"
  override def version: String = "1.0.0"
  
  override def createReader(config: DatabaseConfig): BinlogReader = {
    // 创建 PostgreSQL WAL 读取器
    new PostgreSQLWALReader(config)
  }
  
  override def createCatalog(config: DatabaseConfig): CatalogService = {
    // 创建 PostgreSQL 目录服务
    new PostgreSQLCatalogService(config)
  }
  
  override def createNormalizer(
    catalog: CatalogService,
    sourceDatabase: String
  ): EventNormalizer = {
    // 创建 PostgreSQL 事件标准化器
    new PostgreSQLEventNormalizer(catalog, sourceDatabase)
  }
  
  override def getTypeMapper(): TypeMapper = {
    new PostgreSQLTypeMapper()
  }
}
```

### 步骤 3: 实现 SinkConnector（如果是目标）

```scala
package cn.xuyinyin.cdc.connector.clickhouse

class ClickHouseSinkConnector extends SinkConnector {
  override def name: String = "clickhouse"
  override def version: String = "1.0.0"
  
  override def createWriter(config: DatabaseConfig): DataWriter = {
    new ClickHouseDataWriter(config)
  }
  
  override def getTypeMapper(): TypeMapper = {
    new ClickHouseTypeMapper()
  }
}
```

### 步骤 4: 实现 DataWriter

```scala
class ClickHouseDataWriter(config: DatabaseConfig) extends DataWriter {
  override def insert(table: TableId, data: Map[String, Any]): Future[Unit] = {
    // 实现插入逻辑
  }
  
  override def update(table: TableId, pk: Map[String, Any], data: Map[String, Any]): Future[Unit] = {
    // 实现更新逻辑（ClickHouse 可能需要特殊处理）
  }
  
  override def delete(table: TableId, pk: Map[String, Any]): Future[Unit] = {
    // 实现删除逻辑
  }
  
  override def close(): Unit = {
    // 清理资源
  }
}
```

### 步骤 5: 注册 Connector

在 `ConnectorBootstrap.scala` 中注册新的 Connector：

```scala
private def registerPostgreSQLConnectors(): Unit = {
  ConnectorRegistry.registerSource(PostgreSQLSourceConnector())
  logger.info("PostgreSQL connectors registered")
}

private def registerClickHouseConnectors(): Unit = {
  ConnectorRegistry.registerSink(ClickHouseSinkConnector())
  logger.info("ClickHouse connectors registered")
}

def initialize(): Unit = {
  // ... 现有代码 ...
  registerPostgreSQLConnectors()
  registerClickHouseConnectors()
  // ...
}
```

### 步骤 6: 使用新的 Connector

在配置文件中指定新的 connector 类型：

```hocon
cdc {
  source-type = "postgresql"
  target-type = "clickhouse"
  # ...
}
```

## 类型系统

### 通用数据类型

系统定义了一套数据库无关的通用类型：

- **整数类型**: `TinyIntType`, `SmallIntType`, `IntType`, `BigIntType`
- **浮点类型**: `FloatType`, `DoubleType`
- **定点类型**: `DecimalType(precision, scale)`
- **字符串类型**: `VarCharType(length)`, `CharType(length)`, `TextType`, `LongTextType`
- **二进制类型**: `BlobType`, `VarBinaryType(length)`
- **日期时间类型**: `DateType`, `TimeType`, `DateTimeType`, `TimestampType`
- **其他类型**: `BooleanType`, `JsonType`

### 类型映射流程

```
MySQL DECIMAL(10,2) 
  → [MySQLTypeMapper] → 
DecimalType(10, 2) 
  → [StarRocksTypeMapper] → 
StarRocks DECIMAL64(10,2)
```

## 最佳实践

### 1. 幂等性保证

所有 DataWriter 实现必须保证幂等性：

- **INSERT**: 使用 UPSERT 语义（如 MySQL 的 `INSERT ... ON DUPLICATE KEY UPDATE`）
- **UPDATE**: 基于主键更新，不依赖 before 值
- **DELETE**: 忽略记录不存在的错误

### 2. 批量优化

实现 `batchInsert` 方法以优化快照阶段的性能：

```scala
override def batchInsert(table: TableId, rows: Seq[Map[String, Any]]): Future[Unit] = {
  // 使用数据库特定的批量插入优化
  // 如 MySQL 的 LOAD DATA INFILE
  // 或 StarRocks 的 Stream Load
}
```

### 3. 错误处理

实现健壮的错误处理和重试机制：

```scala
def insertWithRetry(table: TableId, data: Map[String, Any], retries: Int = 3): Future[Unit] = {
  insert(table, data).recoverWith {
    case ex: TransientException if retries > 0 =>
      logger.warn(s"Transient error, retrying... ($retries left)")
      insertWithRetry(table, data, retries - 1)
    case ex =>
      logger.error(s"Permanent error: ${ex.getMessage}")
      Future.failed(ex)
  }
}
```

### 4. 资源管理

正确管理连接池和其他资源：

```scala
class MyDataWriter(config: DatabaseConfig) extends DataWriter {
  private val connectionPool = createConnectionPool(config)
  
  override def close(): Unit = {
    connectionPool.close()
    logger.info("Connection pool closed")
  }
}
```

## 性能考虑

### 1. 连接池

使用连接池避免频繁创建连接：

```scala
private val hikariConfig = new HikariConfig()
hikariConfig.setJdbcUrl(jdbcUrl)
hikariConfig.setMaximumPoolSize(config.connectionPool.maxPoolSize)
hikariConfig.setMinimumIdle(config.connectionPool.minIdle)
private val dataSource = new HikariDataSource(hikariConfig)
```

### 2. 批量写入

在快照阶段使用批量写入：

```scala
override def batchInsert(table: TableId, rows: Seq[Map[String, Any]]): Future[Unit] = {
  val batchSize = 1000
  Future.sequence(
    rows.grouped(batchSize).map(batch => executeBatch(table, batch))
  ).map(_ => ())
}
```

### 3. 异步处理

使用异步 API 提高并发性能：

```scala
override def insert(table: TableId, data: Map[String, Any]): Future[Unit] = Future {
  // 异步执行插入操作
  blocking {
    Using.resource(dataSource.getConnection()) { conn =>
      // 执行 SQL
    }
  }
}
```

## 测试

### 单元测试

测试 TypeMapper：

```scala
class PostgreSQLTypeMapperTest extends AnyFlatSpec with Matchers {
  val mapper = new PostgreSQLTypeMapper()
  
  "PostgreSQLTypeMapper" should "map INTEGER to IntType" in {
    mapper.toGenericType("INTEGER") shouldBe IntType
  }
  
  it should "map IntType to INTEGER" in {
    mapper.toNativeType(IntType) shouldBe "INTEGER"
  }
}
```

### 集成测试

测试完整的 CDC 流程：

```scala
class PostgreSQLToStarRocksTest extends AnyFlatSpec {
  "CDC" should "sync data from PostgreSQL to StarRocks" in {
    val config = CDCConfig(
      taskName = "test",
      sourceType = "postgresql",
      targetType = "starrocks",
      // ...
    )
    
    val engine = CDCEngine(config)
    engine.start()
    
    // 验证数据同步
    // ...
  }
}
```

## 故障排查

### 查看已注册的 Connector

```scala
val sources = ConnectorRegistry.listSources()
val sinks = ConnectorRegistry.listSinks()
println(s"Available sources: ${sources.mkString(", ")}")
println(s"Available sinks: ${sinks.mkString(", ")}")
```

### 验证 Connector 配置

```scala
val connector = ConnectorRegistry.getSource("mysql")
connector.validateConfig(config) match {
  case Some(error) => println(s"Config error: $error")
  case None => println("Config is valid")
}
```

### 查看 Connector 功能

```scala
val features = connector.supportedFeatures()
println(s"Supported features: ${features.mkString(", ")}")
```

## 未来扩展

计划支持的 Connector：

- [ ] PostgreSQL Source Connector
- [ ] ClickHouse Sink Connector
- [ ] Kafka Sink Connector
- [ ] Doris Sink Connector
- [ ] TiDB Source/Sink Connector
- [ ] MongoDB Source Connector

## 参考资料

- [Debezium Connector 架构](https://debezium.io/documentation/reference/architecture.html)
- [Flink CDC Connectors](https://github.com/ververica/flink-cdc-connectors)
- [StarRocks 文档](https://docs.starrocks.io/)
