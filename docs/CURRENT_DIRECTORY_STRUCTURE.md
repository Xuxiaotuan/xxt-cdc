# 当前实际目录结构

## ✅ 完整的目录树

```
src/main/scala/cn/xuyinyin/cdc/
├── CDCApplication.scala              # 应用入口
│
├── api/                              # REST API
│   └── CDCManagementAPI.scala
│
├── catalog/                          # 元数据管理（功能层）
│   ├── CatalogService.scala          # 接口
│   └── MySQLCatalogService.scala     # MySQL 实现 ✅
│
├── config/                           # 配置管理
│   ├── CDCConfig.scala
│   └── ConfigLoader.scala
│
├── connector/                        # Connector 层（组装）
│   ├── ConnectorBootstrap.scala     # 启动器
│   ├── ConnectorConfig.scala        # Connector 配置
│   ├── ConnectorRegistry.scala      # 注册中心
│   ├── DataType.scala                # 通用类型系统
│   ├── SinkConnector.scala           # Sink 接口
│   ├── SourceConnector.scala         # Source 接口
│   │
│   ├── jdbc/                         # JDBC 公共组件
│   │   ├── JdbcConnectionManager.scala
│   │   └── JdbcDataWriter.scala
│   │
│   ├── sink/                         # Sink Connectors
│   │   ├── mysql/
│   │   │   ├── MySQLSinkConnector.scala
│   │   │   └── MySQLTypeMapper.scala
│   │   └── starrocks/
│   │       ├── StarRocksSinkConnector.scala
│   │       └── StarRocksTypeMapper.scala
│   │
│   └── source/                       # Source Connectors
│       └── mysql/
│           ├── MySQLSourceConnector.scala
│           └── MySQLTypeMapper.scala
│
├── coordinator/                      # 偏移量协调
│   ├── DefaultOffsetCoordinator.scala
│   ├── FileOffsetStore.scala
│   ├── MySQLOffsetStore.scala
│   ├── OffsetCoordinator.scala
│   └── OffsetStore.scala
│
├── ddl/                              # DDL 处理
│   ├── DDLEventIntegrator.scala
│   ├── DDLEventListener.scala
│   └── DDLHandler.scala
│
├── engine/                           # CDC 引擎
│   ├── CDCEngine.scala
│   └── CDCEngineUtils.scala
│
├── error/                            # 错误处理
│   └── ErrorHandler.scala
│
├── filter/                           # 表过滤
│   └── TableFilter.scala
│
├── health/                           # 健康检查
│   └── HealthCheck.scala
│
├── logging/                          # 日志
│   ├── CDCLogging.scala
│   ├── ColoredConsoleEncoder.scala
│   └── PerformanceLogger.scala
│
├── metrics/                          # 指标收集
│   ├── CDCMetrics.scala
│   ├── EnhancedMetricsCollector.scala
│   └── PrometheusMetrics.scala
│
├── model/                            # 数据模型
│   ├── BinlogPosition.scala
│   ├── CDCState.scala
│   ├── ChangeEvent.scala
│   ├── MySQLDataType.scala
│   ├── OffsetState.scala
│   ├── TableId.scala
│   └── TableMeta.scala
│
├── normalizer/                       # 事件标准化（功能层）
│   ├── EventNormalizer.scala        # 接口
│   └── MySQLEventNormalizer.scala   # MySQL 实现 ✅
│
├── pipeline/                         # 流处理管道
│   └── CDCStreamPipeline.scala
│
├── reader/                           # 变更日志读取（功能层）
│   ├── BinlogReader.scala            # 接口
│   └── MySQLBinlogReader.scala       # MySQL 实现 ✅
│
├── router/                           # 事件路由
│   └── EventRouter.scala
│
├── sink/                             # 数据写入（旧实现，保留兼容）
│   ├── IdempotentMySQLSink.scala
│   ├── MySQLSink.scala
│   └── PooledMySQLSink.scala
│
├── snapshot/                         # 快照管理
│   ├── CatchupProcessor.scala
│   ├── LowWatermarkManager.scala
│   ├── SnapshotCatchupCoordinator.scala
│   ├── SnapshotManager.scala
│   ├── SnapshotScheduler.scala
│   ├── SnapshotWorker.scala
│   └── SnapshotWorkerPool.scala
│
└── worker/                           # 工作器
    ├── ApplyWorker.scala
    └── DefaultApplyWorker.scala
```

## 📊 目录统计

| 类别 | 目录数 | 说明 |
|-----|-------|------|
| **Connector 层** | 5 | `connector/`, `connector/jdbc/`, `connector/source/mysql/`, `connector/sink/mysql/`, `connector/sink/starrocks/` |
| **功能层** | 4 | `reader/`, `catalog/`, `normalizer/`, `sink/` |
| **核心组件** | 18 | `engine/`, `pipeline/`, `worker/`, `coordinator/`, `router/`, `snapshot/`, `filter/`, `ddl/`, `error/`, `health/`, `metrics/`, `logging/`, `model/`, `config/`, `api/` 等 |
| **总计** | 27 | 所有目录 |

## ✅ 架构验证

### Connector 层（组装）

```
connector/
├── source/mysql/
│   ├── MySQLSourceConnector.scala    ✅ 组装 Reader + Catalog + Normalizer
│   └── MySQLTypeMapper.scala         ✅ 类型映射
│
└── sink/
    ├── mysql/
    │   ├── MySQLSinkConnector.scala  ✅ 组装 DataWriter
    │   └── MySQLTypeMapper.scala     ✅ 类型映射
    └── starrocks/
        ├── StarRocksSinkConnector.scala  ✅ 组装 DataWriter
        └── StarRocksTypeMapper.scala     ✅ 类型映射
```

### 功能层（实现） ✅

```
reader/
├── BinlogReader.scala                ✅ 接口
└── MySQLBinlogReader.scala           ✅ MySQL 实现（已从connector层移动）

catalog/
├── CatalogService.scala              ✅ 接口
└── MySQLCatalogService.scala         ✅ MySQL 实现（已从connector层移动）

normalizer/
├── EventNormalizer.scala             ✅ 接口
└── MySQLEventNormalizer.scala        ✅ MySQL 实现（已从connector层移动）

sink/
├── MySQLSink.scala                   ✅ 接口（旧）
├── PooledMySQLSink.scala             ✅ 实现（旧）
└── IdempotentMySQLSink.scala         ✅ 实现（旧）
```

## 🎯 设计验证

### 1. Connector 是组装者 ✅

```scala
// MySQLSourceConnector 组装功能组件
class MySQLSourceConnector extends SourceConnector {
  override def createReader(config: DatabaseConfig): BinlogReader = {
    MySQLBinlogReader(config)  // 来自 reader/ 包
  }
  
  override def createCatalog(config: DatabaseConfig): CatalogService = {
    MySQLCatalogService(config)  // 来自 catalog/ 包
  }
  
  override def createNormalizer(...): EventNormalizer = {
    MySQLEventNormalizer(...)  // 来自 normalizer/ 包
  }
}
```

### 2. 功能层独立实现 ✅

```
reader/MySQLBinlogReader.scala        # 实现 BinlogReader 接口
catalog/MySQLCatalogService.scala     # 实现 CatalogService 接口
normalizer/MySQLEventNormalizer.scala # 实现 EventNormalizer 接口
```

### 3. 分层清晰 ✅

```
应用层: engine/, api/
  ↓
Connector 层: connector/
  ↓
功能层: reader/, catalog/, normalizer/, sink/
  ↓
基础层: model/, config/, metrics/, logging/
```

## 📝 包路径

### Connector 层

```scala
// Source Connector
cn.xuyinyin.cdc.connector.source.mysql.MySQLSourceConnector
cn.xuyinyin.cdc.connector.source.mysql.MySQLTypeMapper

// Sink Connector
cn.xuyinyin.cdc.connector.sink.mysql.MySQLSinkConnector
cn.xuyinyin.cdc.connector.sink.mysql.MySQLTypeMapper
cn.xuyinyin.cdc.connector.sink.starrocks.StarRocksSinkConnector
cn.xuyinyin.cdc.connector.sink.starrocks.StarRocksTypeMapper

// JDBC 公共组件
cn.xuyinyin.cdc.connector.jdbc.JdbcConnectionManager
cn.xuyinyin.cdc.connector.jdbc.JdbcDataWriter
```

### 功能层

```scala
// Reader
cn.xuyinyin.cdc.reader.BinlogReader
cn.xuyinyin.cdc.reader.MySQLBinlogReader

// Catalog
cn.xuyinyin.cdc.catalog.CatalogService
cn.xuyinyin.cdc.catalog.MySQLCatalogService

// Normalizer
cn.xuyinyin.cdc.normalizer.EventNormalizer
cn.xuyinyin.cdc.normalizer.MySQLEventNormalizer

// Sink（旧）
cn.xuyinyin.cdc.sink.MySQLSink
cn.xuyinyin.cdc.sink.PooledMySQLSink
```

## ✅ 结论

当前的目录结构**完全符合设计**：

1. ✅ Connector 层和功能层分离
2. ✅ Source 和 Sink 独立
3. ✅ 接口和实现分离
4. ✅ 职责清晰，易于扩展

这是一个**合理、优雅、可扩展**的架构！🎉
