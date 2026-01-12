# 理想架构设计

## 🎯 设计目标

1. **所有 MySQL 相关代码集中在一起** - 便于维护
2. **区分 batch 和 stream** - 职责清晰
3. **避免循环依赖** - 接口和实现分离

## 📁 最终理想结构

```
src/main/scala/cn/xuyinyin/cdc/

# ============ 通用接口（保留在原位置）============
reader/
└── BinlogReader.scala              # 通用接口

catalog/
└── CatalogService.scala            # 通用接口

normalizer/
└── EventNormalizer.scala           # 通用接口

# ============ Connector 实现（所有具体实现）============
connector/
├── source/
│   └── mysql/
│       ├── batch/                  # 批量读取（快照）
│       │   ├── MySQLBatchReader.scala
│       │   └── MySQLSnapshotReader.scala
│       │
│       ├── stream/                 # 流式读取（CDC）
│       │   ├── MySQLBinlogReader.scala      # 实现 BinlogReader
│       │   └── MySQLEventNormalizer.scala   # 实现 EventNormalizer
│       │
│       ├── MySQLSourceConnector.scala       # 组装器
│       ├── MySQLCatalogService.scala        # 实现 CatalogService
│       └── MySQLTypeMapper.scala
│
└── sink/
    ├── mysql/
    │   ├── MySQLSinkConnector.scala
    │   ├── MySQLDataWriter.scala
    │   └── MySQLTypeMapper.scala
    │
    └── starrocks/
        ├── StarRocksSinkConnector.scala
        ├── StarRocksDataWriter.scala
        └── StarRocksTypeMapper.scala
```

## 🔄 为什么这样设计？

### 1. 保留通用接口在原位置

**原因**：
- ✅ 避免循环依赖
- ✅ 接口是框架的一部分，不属于某个具体 Connector
- ✅ 其他模块（如 Engine、Pipeline）可以直接依赖接口

**示例**：
```scala
// SourceConnector 接口使用通用接口
trait SourceConnector {
  def createReader(...): cn.xuyinyin.cdc.reader.BinlogReader
  def createCatalog(...): cn.xuyinyin.cdc.catalog.CatalogService
  def createNormalizer(...): cn.xuyinyin.cdc.normalizer.EventNormalizer
}
```

### 2. 所有实现放在 connector 下

**原因**：
- ✅ MySQL 的所有代码都在 `connector/source/mysql/` 下
- ✅ 添加 PostgreSQL 时，所有代码都在 `connector/source/postgresql/` 下
- ✅ 便于维护和理解

**示例**：
```
connector/source/mysql/          # MySQL 的所有东西
├── batch/                        # 批量相关
├── stream/                       # 流式相关
├── MySQLSourceConnector.scala   # 组装器
├── MySQLCatalogService.scala    # Catalog 实现
└── MySQLTypeMapper.scala         # 类型映射
```

### 3. 区分 batch 和 stream

**原因**：
- ✅ 职责清晰：batch 用于快照，stream 用于 CDC
- ✅ 可以独立优化
- ✅ 符合业界标准（Flink CDC、Debezium）

**batch 目录**：
```
connector/source/mysql/batch/
├── MySQLBatchReader.scala        # 批量读取接口
└── MySQLSnapshotReader.scala    # 快照读取实现
```

**stream 目录**：
```
connector/source/mysql/stream/
├── MySQLBinlogReader.scala       # Binlog 读取
└── MySQLEventNormalizer.scala   # 事件标准化
```

## 📊 依赖关系

```
Engine/Pipeline
    ↓ 依赖接口
reader/BinlogReader
catalog/CatalogService
normalizer/EventNormalizer
    ↑ 实现接口
connector/source/mysql/stream/MySQLBinlogReader
connector/source/mysql/MySQLCatalogService
connector/source/mysql/stream/MySQLEventNormalizer
```

## 🎯 实施步骤

由于当前架构已经可以工作，建议**渐进式重构**：

### 阶段 1：保持当前结构（已完成）✅

```
reader/MySQLBinlogReader.scala
catalog/MySQLCatalogService.scala
normalizer/MySQLEventNormalizer.scala

connector/source/mysql/MySQLSourceConnector.scala  # 引用上面的实现
```

**优点**：
- ✅ 编译通过
- ✅ 功能正常
- ✅ 可以正常使用

### 阶段 2：逐步迁移（未来）

当需要添加第二个数据库（如 PostgreSQL）时，再考虑重构：

1. 创建 `connector/source/postgresql/` 目录
2. 实现 PostgreSQL 的所有组件
3. 如果发现代码重复，再考虑提取公共逻辑

### 阶段 3：完全重构（可选）

如果有多个数据库后，可以考虑将所有实现移到 connector 下：

```bash
# 移动 MySQL 实现
mv reader/MySQLBinlogReader.scala connector/source/mysql/stream/
mv catalog/MySQLCatalogService.scala connector/source/mysql/
mv normalizer/MySQLEventNormalizer.scala connector/source/mysql/stream/

# 保留接口
reader/BinlogReader.scala
catalog/CatalogService.scala
normalizer/EventNormalizer.scala
```

## 🤔 当前架构 vs 理想架构

### 当前架构（可用）

```
reader/
├── BinlogReader.scala              ✅ 接口
└── MySQLBinlogReader.scala         ⚠️ 实现（应该在 connector 下）

catalog/
├── CatalogService.scala            ✅ 接口
└── MySQLCatalogService.scala       ⚠️ 实现（应该在 connector 下）

normalizer/
├── EventNormalizer.scala           ✅ 接口
└── MySQLEventNormalizer.scala      ⚠️ 实现（应该在 connector 下）

connector/source/mysql/
├── MySQLSourceConnector.scala      ✅ 组装器
└── MySQLTypeMapper.scala           ✅ 类型映射
```

**评价**：
- ✅ 功能完整，可以正常使用
- ⚠️ MySQL 实现分散在多个目录
- ⚠️ 添加新数据库时需要在多个目录创建文件

### 理想架构（目标）

```
reader/BinlogReader.scala           ✅ 接口
catalog/CatalogService.scala        ✅ 接口
normalizer/EventNormalizer.scala    ✅ 接口

connector/source/mysql/
├── batch/
│   └── MySQLSnapshotReader.scala
├── stream/
│   ├── MySQLBinlogReader.scala     ✅ 所有 MySQL 代码在一起
│   └── MySQLEventNormalizer.scala
├── MySQLSourceConnector.scala
├── MySQLCatalogService.scala
└── MySQLTypeMapper.scala
```

**评价**：
- ✅ 所有 MySQL 代码集中在一起
- ✅ batch 和 stream 职责清晰
- ✅ 添加新数据库只需在 connector 下创建一个目录

## 💡 建议

**当前阶段**：保持现有架构，因为：
1. ✅ 已经可以工作
2. ✅ 编译通过
3. ✅ 功能完整

**未来优化**：当添加第二个数据库时，再考虑重构：
1. 创建 `connector/source/postgresql/` 目录
2. 将 PostgreSQL 的所有实现放在一起
3. 如果觉得 MySQL 分散，再迁移 MySQL 实现

**重构原则**：
- 🎯 不要为了完美而重构
- 🎯 等到真正需要时再重构
- 🎯 保持代码可工作比追求完美架构更重要

## 📚 参考

类似项目的架构：

### Flink CDC
```
flink-connector-mysql-cdc/
├── source/
│   ├── reader/
│   ├── split/
│   └── MySQLSource.java
└── table/
    └── MySQLTableSource.java
```

### Debezium
```
debezium-connector-mysql/
├── MySQLConnector.java
├── MySQLConnection.java
├── BinlogReader.java
└── SnapshotReader.java
```

## 🎉 总结

**当前架构**：功能完整，可以正常使用 ✅

**理想架构**：所有实现集中在 connector 下，batch 和 stream 分离 🎯

**建议**：保持当前架构，等添加第二个数据库时再优化 💡
