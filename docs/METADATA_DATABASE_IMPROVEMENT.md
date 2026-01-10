# 元数据库分离改进

## 改进概述

将 CDC 偏移量存储从源数据库分离到独立的元数据库，支持多任务共享同一个元数据库。

## 改进动机

### 之前的问题

1. **元数据污染业务数据**：`cdc_offsets` 表创建在源数据库中，与业务数据混在一起
2. **Binlog 噪音**：表的 DDL 操作会被记录到 binlog，产生不必要的事件和警告
3. **多任务冲突**：每个 CDC 任务都会创建独立的表，无法共享
4. **管理困难**：元数据分散在各个源数据库中，难以统一管理

### 改进后的优势

1. **数据分离**：元数据和业务数据完全分离，不污染源数据库
2. **多任务共享**：多个 CDC 任务可以共享同一个元数据库，通过 `task_name` 区分
3. **统一管理**：所有 CDC 任务的元数据集中存储，便于监控和管理
4. **Binlog 清洁**：不会在源库 binlog 中产生 DDL 事件

## 技术实现

### 1. 配置模型变更

**文件：** `src/main/scala/cn/xuyinyin/cdc/config/CDCConfig.scala`

增加了两个新字段：
- `taskName: String` - CDC 任务名称，用于区分不同的任务
- `metadata: DatabaseConfig` - 元数据库配置

```scala
case class CDCConfig(
  taskName: String,              // 新增
  source: DatabaseConfig,
  target: DatabaseConfig,
  metadata: DatabaseConfig,      // 新增
  filter: FilterConfig,
  parallelism: ParallelismConfig,
  offset: OffsetConfig
)
```

### 2. MySQLOffsetStore 改进

**文件：** `src/main/scala/cn/xuyinyin/cdc/coordinator/MySQLOffsetStore.scala`

**主要变更：**

1. **构造函数增加 taskName 参数**：
```scala
class MySQLOffsetStore(
  config: DatabaseConfig,
  taskName: String,              // 新增
  tableName: String = "cdc_offsets"
)
```

2. **表结构改进**：
```sql
CREATE TABLE IF NOT EXISTS cdc_offsets (
  task_name VARCHAR(255) NOT NULL,      -- 新增：任务名称
  position_type VARCHAR(20) NOT NULL,
  position_value TEXT NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_name),               -- 改为使用 task_name 作为主键
  INDEX idx_updated_at (updated_at)
)
```

3. **CRUD 操作改进**：
- `save()`: 使用 `task_name` 作为条件
- `load()`: 使用 `task_name` 作为条件
- `delete()`: 使用 `task_name` 作为条件

### 3. 配置加载器更新

**文件：** `src/main/scala/cn/xuyinyin/cdc/config/ConfigLoader.scala`

增加了对新配置的加载和验证：
- 加载 `task-name` 配置
- 加载 `metadata` 数据库配置
- 验证 `task-name` 不能为空
- 验证 `metadata` 数据库配置的有效性

### 4. CDCEngine 更新

**文件：** `src/main/scala/cn/xuyinyin/cdc/engine/CDCEngine.scala`

修改 `initializeOffsetStore()` 方法，使用 `config.metadata` 而不是 `config.target`：

```scala
private def initializeOffsetStore(): Future[Unit] = Future {
  offsetStore = Some(config.offset.storeType match {
    case cn.xuyinyin.cdc.config.MySQLOffsetStore =>
      MySQLOffsetStore(config.metadata, config.taskName)  // 使用 metadata 配置
    case cn.xuyinyin.cdc.config.FileOffsetStore =>
      val path = config.offset.storeConfig.getOrElse("path", "./data/offsets/offset.txt")
      FileOffsetStore(path)
  })
  logger.debug("Offset store initialized")
}
```

### 5. 配置文件更新

**文件：** `src/main/resources/reference.conf`

增加了新的配置段：

```hocon
cdc {
  # CDC 任务名称
  task-name = "default-cdc-task"
  
  # ... 其他配置 ...
  
  # 元数据库配置
  metadata {
    host = "100.82.226.63"
    port = 31765
    username = "root"
    password = "asd123456"
    database = "xxt_cdc"        # 元数据库名称
    
    connection-pool {
      max-pool-size = 5
      min-idle = 1
      connection-timeout = 30s
    }
  }
}
```

## 使用指南

### 1. 配置元数据库

在配置文件中增加 `metadata` 配置段：

```hocon
cdc {
  task-name = "my-cdc-task"
  
  metadata {
    host = "localhost"
    port = 3306
    username = "cdc_user"
    password = "password"
    database = "xxt_cdc"
    
    connection-pool {
      max-pool-size = 5
      min-idle = 1
      connection-timeout = 30s
    }
  }
}
```

### 2. 创建元数据库

```sql
CREATE DATABASE IF NOT EXISTS xxt_cdc 
  CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;
```

### 3. 授权

```sql
GRANT ALL PRIVILEGES ON xxt_cdc.* TO 'cdc_user'@'%';
FLUSH PRIVILEGES;
```

### 4. 多任务配置示例

**任务 1：用户同步**
```hocon
cdc {
  task-name = "user-sync"
  metadata {
    database = "xxt_cdc"
  }
}
```

**任务 2：订单同步**
```hocon
cdc {
  task-name = "order-sync"
  metadata {
    database = "xxt_cdc"  # 共享同一个元数据库
  }
}
```

两个任务会在 `xxt_cdc.cdc_offsets` 表中创建不同的记录：
```sql
SELECT * FROM xxt_cdc.cdc_offsets;
+-----------+---------------+-------------------------+---------------------+
| task_name | position_type | position_value          | updated_at          |
+-----------+---------------+-------------------------+---------------------+
| user-sync | FILE          | binlog.000012:38592408  | 2026-01-10 16:17:03 |
| order-sync| FILE          | binlog.000015:12345678  | 2026-01-10 16:20:15 |
+-----------+---------------+-------------------------+---------------------+
```

## 迁移指南

### 从旧版本迁移

如果你已经在使用旧版本（偏移量存储在目标库），需要进行以下迁移：

1. **创建元数据库**：
```sql
CREATE DATABASE xxt_cdc;
```

2. **迁移偏移量数据**：
```sql
-- 从目标库复制偏移量到元数据库
INSERT INTO xxt_cdc.cdc_offsets (task_name, position_type, position_value, updated_at)
SELECT 'your-task-name', position_type, position_value, updated_at
FROM target_db.cdc_offsets
WHERE id = 1;
```

3. **更新配置文件**：
```hocon
cdc {
  task-name = "your-task-name"  # 新增
  metadata {                     # 新增
    database = "xxt_cdc"
  }
}
```

4. **重启 CDC 服务**

5. **验证**：
```sql
-- 检查偏移量是否正确加载
SELECT * FROM xxt_cdc.cdc_offsets WHERE task_name = 'your-task-name';
```

6. **清理旧数据**（可选）：
```sql
-- 确认新系统运行正常后，可以删除目标库中的旧表
DROP TABLE IF EXISTS target_db.cdc_offsets;
```

## 监控和管理

### 查看所有任务的偏移量

```sql
SELECT 
  task_name,
  position_type,
  position_value,
  updated_at
FROM xxt_cdc.cdc_offsets
ORDER BY updated_at DESC;
```

### 查看特定任务的偏移量

```sql
SELECT * FROM xxt_cdc.cdc_offsets 
WHERE task_name = 'user-sync';
```

### 重置任务偏移量

```sql
-- 删除偏移量，任务将从配置的起始位置重新开始
DELETE FROM xxt_cdc.cdc_offsets 
WHERE task_name = 'user-sync';
```

### 手动设置偏移量

```sql
-- 设置任务从特定位置开始
REPLACE INTO xxt_cdc.cdc_offsets (task_name, position_type, position_value)
VALUES ('user-sync', 'FILE', 'binlog.000012:38592408');
```

## 最佳实践

1. **使用独立的元数据库**：不要将元数据库与源库或目标库混用
2. **定期备份元数据库**：偏移量数据对于崩溃恢复至关重要
3. **使用有意义的任务名称**：如 `user-sync`, `order-sync` 而不是 `task1`, `task2`
4. **监控元数据库连接**：虽然连接数较少，但仍需监控
5. **定期清理历史数据**：如果任务已停止，可以删除对应的偏移量记录

## 性能影响

- **连接数减少**：元数据库只需要少量连接（默认 max=5）
- **写入频率**：偏移量提交频率由 `commit-interval` 控制（默认 5s）
- **读取频率**：只在启动时读取一次
- **存储空间**：每个任务只占用一行记录，空间占用极小

## 故障排查

### 问题：无法连接到元数据库

**症状**：启动时报错 "Failed to initialize offset table"

**解决方案**：
1. 检查元数据库是否存在
2. 检查用户权限
3. 检查网络连接
4. 检查配置文件中的连接信息

### 问题：找不到偏移量

**症状**：日志显示 "No previous committed position found"

**解决方案**：
1. 检查 `task-name` 是否正确
2. 检查元数据库中是否有对应的记录
3. 如果是首次启动，这是正常的

### 问题：偏移量未更新

**症状**：`updated_at` 字段长时间不变

**解决方案**：
1. 检查 CDC 服务是否正常运行
2. 检查是否有数据变更
3. 检查 `commit-interval` 配置
4. 查看日志中是否有错误信息

## 相关文件

- `src/main/scala/cn/xuyinyin/cdc/config/CDCConfig.scala` - 配置模型
- `src/main/scala/cn/xuyinyin/cdc/coordinator/MySQLOffsetStore.scala` - 偏移量存储实现
- `src/main/scala/cn/xuyinyin/cdc/config/ConfigLoader.scala` - 配置加载器
- `src/main/scala/cn/xuyinyin/cdc/engine/CDCEngine.scala` - CDC 引擎
- `src/main/resources/reference.conf` - 默认配置
- `docs/example.conf` - 配置示例

## 版本信息

- **实施日期**：2026-01-10
- **影响范围**：配置模型、偏移量存储、配置加载
- **向后兼容**：需要配置迁移
- **测试状态**：编译通过

## 后续改进

1. 支持元数据库的高可用配置
2. 增加元数据库的健康检查
3. 提供偏移量管理 API
4. 增加偏移量历史记录功能
5. 支持偏移量的自动备份和恢复
