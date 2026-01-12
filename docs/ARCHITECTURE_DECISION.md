# 架构决策记录

## 决策：保持当前架构，暂不重构

**日期**：2026-01-12

**状态**：✅ 已决定

## 背景

在讨论中，我们考虑了两种架构方案：

### 方案 A：当前架构（已实现）

```
reader/
├── BinlogReader.scala              # 接口
└── MySQLBinlogReader.scala         # MySQL 实现

catalog/
├── CatalogService.scala            # 接口
└── MySQLCatalogService.scala       # MySQL 实现

normalizer/
├── EventNormalizer.scala           # 接口
└── MySQLEventNormalizer.scala      # MySQL 实现

connector/source/mysql/
├── MySQLSourceConnector.scala      # 组装器
└── MySQLTypeMapper.scala
```

### 方案 B：理想架构（未实现）

```
reader/BinlogReader.scala           # 接口
catalog/CatalogService.scala        # 接口
normalizer/EventNormalizer.scala    # 接口

connector/source/mysql/
├── batch/
│   └── MySQLSnapshotReader.scala
├── stream/
│   ├── MySQLBinlogReader.scala
│   └── MySQLEventNormalizer.scala
├── MySQLSourceConnector.scala
├── MySQLCatalogService.scala
└── MySQLTypeMapper.scala
```

## 决策

**选择方案 A：保持当前架构**

## 理由

### 1. 当前架构已经可以工作 ✅

- ✅ 编译通过
- ✅ 功能完整
- ✅ 测试通过
- ✅ 可以正常使用

### 2. 重构成本 vs 收益

**重构成本**：
- 移动文件
- 更新所有 import
- 更新文档
- 重新测试
- 可能引入 bug

**重构收益**：
- MySQL 代码更集中
- 目录结构更清晰

**结论**：当前只有 MySQL 一个数据源，收益不明显

### 3. 避免过度设计

> "不要为了完美而重构，等到真正需要时再重构"

当前只有 MySQL，没有必要为了"可能的未来需求"而重构。

### 4. 渐进式演进

**更好的策略**：
1. 保持当前架构
2. 当添加第二个数据库（如 PostgreSQL）时
3. 如果发现代码分散导致维护困难
4. 再考虑重构

## 当前架构的优点

### 1. 清晰的接口定义

```
reader/BinlogReader.scala           # 所有人都知道接口在这里
catalog/CatalogService.scala        # 接口定义清晰
normalizer/EventNormalizer.scala    # 易于查找
```

### 2. 实现和接口分离

```
reader/
├── BinlogReader.scala              # 接口（框架层）
└── MySQLBinlogReader.scala         # 实现（MySQL 特定）
```

这符合"接口隔离原则"。

### 3. 符合现有习惯

大多数项目都是这样组织的：
- `service/` - 接口
- `service/impl/` - 实现

或者：
- `api/` - 接口
- `impl/` - 实现

### 4. 避免循环依赖

如果把接口也移到 connector 下，会导致：
```
connector/source/mysql/BinlogReader.scala
    ↑
connector/SourceConnector.scala
    ↓
connector/source/mysql/MySQLSourceConnector.scala
```

这会造成包之间的循环依赖。

## 理想架构的问题

### 1. 接口应该放在哪里？

如果把 `BinlogReader` 接口也移到 `connector/source/mysql/` 下：

**问题**：
- PostgreSQL 也需要实现 `BinlogReader`
- 难道要 `import cn.xuyinyin.cdc.connector.source.mysql.BinlogReader`？
- 这不合理！

**解决方案**：
- 接口必须保留在通用位置
- 只移动实现

### 2. 移动实现的价值？

**当前**：
```
reader/MySQLBinlogReader.scala      # 实现
connector/source/mysql/MySQLSourceConnector.scala  # 引用
```

**移动后**：
```
connector/source/mysql/stream/MySQLBinlogReader.scala  # 实现
connector/source/mysql/MySQLSourceConnector.scala      # 引用
```

**价值**：
- ✅ MySQL 代码更集中
- ⚠️ 但只有一个数据库时，价值不大

## 何时重构？

### 触发条件

当满足以下任一条件时，考虑重构：

1. **添加第二个数据库**
   - 如：PostgreSQL、Oracle、SQL Server
   - 此时会发现代码分散的问题

2. **维护困难**
   - 修改 MySQL 相关代码需要在多个目录查找
   - 团队成员反馈目录结构不清晰

3. **代码重复**
   - 多个数据库有大量重复代码
   - 需要提取公共逻辑

### 重构步骤

```bash
# 1. 创建新目录
mkdir -p connector/source/mysql/batch
mkdir -p connector/source/mysql/stream

# 2. 移动实现（保留接口）
mv reader/MySQLBinlogReader.scala connector/source/mysql/stream/
mv catalog/MySQLCatalogService.scala connector/source/mysql/
mv normalizer/MySQLEventNormalizer.scala connector/source/mysql/stream/

# 3. 更新包名
sed -i 's/package cn.xuyinyin.cdc.reader/package cn.xuyinyin.cdc.connector.source.mysql.stream/' ...

# 4. 更新 import
# 更新所有引用这些类的地方

# 5. 测试
sbt compile
sbt test
```

## 最佳实践

### 1. YAGNI 原则

> "You Aren't Gonna Need It" - 你不会需要它

不要为了可能的未来需求而过度设计。

### 2. 渐进式重构

> "Make it work, make it right, make it fast"

1. Make it work - ✅ 当前已完成
2. Make it right - 等到真正需要时
3. Make it fast - 性能优化

### 3. 保持简单

> "Simplicity is the ultimate sophistication"

当前架构简单、清晰、可工作，这就够了。

## 结论

**决定**：保持当前架构 ✅

**原因**：
1. ✅ 当前架构可工作
2. ✅ 避免过度设计
3. ✅ 等到真正需要时再重构

**下一步**：
1. 完善文档
2. 添加测试
3. 优化性能
4. 等待添加第二个数据库的需求

## 参考

- [YAGNI - You Aren't Gonna Need It](https://martinfowler.com/bliki/Yagni.html)
- [Premature Optimization](https://wiki.c2.com/?PrematureOptimization)
- [Refactoring: Improving the Design of Existing Code](https://martinfowler.com/books/refactoring.html)

---

**记录人**：AI Assistant  
**审核人**：待定  
**生效日期**：2026-01-12
