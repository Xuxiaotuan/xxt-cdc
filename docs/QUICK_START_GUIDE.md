# MySQL CDC Service 快速启动指南

## 前提条件

### 1. MySQL 配置要求

源 MySQL 数据库必须启用 binlog：

```ini
[mysqld]
server-id = 1
log-bin = mysql-bin
binlog-format = ROW
gtid-mode = ON
enforce-gtid-consistency = ON
```

### 2. 数据库权限

CDC 服务需要以下权限：

```sql
-- 源数据库（读取权限）
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'root'@'%';

-- 目标数据库（写入权限）
GRANT ALL PRIVILEGES ON test_target.* TO 'root'@'%';
```

## 配置文件

### 主配置文件：`docker/application.conf`

```hocon
cdc {
  # 源数据库配置
  source {
    host = "100.82.226.63"
    port = 31765
    username = "root"
    password = "asd123456"
    database = "test"
  }
  
  # 目标数据库配置
  target {
    host = "100.82.226.63"
    port = 31765
    username = "root"
    password = "asd123456"
    database = "test_target"
  }
  
  # 偏移量配置
  offset {
    store-type = "mysql"
    commit-interval = "5s"
    start-from-latest = true  # true=从最新位置开始（跳过历史），false=从头开始
  }
}
```

### 起始位置配置

CDC 服务支持两种启动模式：

#### 1. 只增量同步（默认）

```hocon
cdc {
  offset {
    start-from-latest = true  # 从最新位置开始
    enable-snapshot = false   # 不执行快照
  }
}
```

- **行为**：跳过所有历史数据，只同步启动后的新变更
- **适用场景**：
  - 已有数据的生产环境，只需要增量同步
  - 测试和开发环境
  - 不需要历史数据的场景

#### 2. 全量+增量同步

```hocon
cdc {
  offset {
    start-from-latest = false  # 从头开始（或使用快照）
    enable-snapshot = true     # 启用快照
  }
}
```

- **行为**：先执行全量快照，然后实时同步增量变更
- **适用场景**：
  - 新环境需要完整数据同步
  - 需要历史数据的场景
  - 生产环境的初始化

**注意**：如果之前已经运行过 CDC 服务并保存了偏移量，会优先使用保存的偏移量，忽略这些配置。

### 环境变量覆盖

可以通过环境变量覆盖配置：

```bash
# 源数据库
export CDC_SOURCE_HOST=100.82.226.63
export CDC_SOURCE_PORT=31765
export CDC_SOURCE_USERNAME=root
export CDC_SOURCE_PASSWORD=asd123456
export CDC_SOURCE_DATABASE=test

# 目标数据库
export CDC_TARGET_HOST=100.82.226.63
export CDC_TARGET_PORT=31765
export CDC_TARGET_USERNAME=root
export CDC_TARGET_PASSWORD=asd123456
export CDC_TARGET_DATABASE=test_target

# 偏移量配置
export CDC_START_FROM_LATEST=true   # 从最新位置开始
export CDC_ENABLE_SNAPSHOT=true     # 启用快照（全量同步）
```

### 快照功能说明

当启用快照时（`enable-snapshot = true`），CDC 服务会：

1. **发现表**：自动发现需要同步的表
2. **记录 Low Watermark**：记录快照开始时的 binlog 位置
3. **执行快照**：为每张表执行全量数据复制
   - 使用 `REPLACE INTO` 实现幂等
   - 支持批量插入（默认 100 行/批）
   - 显示进度日志
4. **记录 High Watermark**：记录快照结束时的 binlog 位置
5. **开始增量同步**：从 High Watermark 开始实时同步

**日志示例**：
```
[INFO] Starting snapshot phase
[INFO] Discovered 2 tables for snapshot
[INFO] Low Watermark: mysql-bin.000001:12345
[INFO] Starting snapshot for table test.users
[INFO] Snapshot progress for users: 10000 rows
[INFO] Snapshot completed for table test.users: 15000 rows
[INFO] Starting snapshot for table test.orders
[INFO] Snapshot completed for table test.orders: 8000 rows
[INFO] Snapshot completed: 2/2 tables, 23000 total rows
[INFO] High Watermark: mysql-bin.000001:12567
[INFO] Starting CDC stream from position: mysql-bin.000001:12567
```

## 启动服务

### 方式 1：使用 SBT

```bash
# 编译
sbt compile

# 运行
sbt run
```

### 方式 2：打包运行

```bash
# 打包
sbt assembly

# 运行
java -jar target/scala-2.13/xxt-cdc-assembly-1.0.0.jar
```

### 方式 3：使用配置文件

```bash
# 使用自定义配置文件
java -Dconfig.file=docker/application.conf -jar target/scala-2.13/xxt-cdc-assembly-1.0.0.jar
```

## 验证服务状态

### 1. 检查日志

成功启动后，你应该看到类似的日志：

```
[INFO] Starting MySQL CDC Engine
[INFO] CDC configuration loaded successfully
[INFO] Configuration validation passed
[INFO] CDC Management API started at http://0:0:0:0:0:0:0:0:8080
[INFO] Starting CDC Engine
[INFO] Entering phase: Init
[INFO] Connected to 100.82.226.63:31765 at mysql-bin.000001/4
```

### 2. 访问管理 API

CDC 服务提供了 HTTP 管理接口（默认端口 8080）：

```bash
# 检查服务状态
curl http://localhost:8080/health

# 查看当前偏移量
curl http://localhost:8080/offset

# 查看表列表
curl http://localhost:8080/tables
```

## 测试数据同步

### 1. 在源数据库插入数据

```sql
USE test;

-- 插入用户
INSERT INTO users (username, email) VALUES ('test_user', 'test@example.com');

-- 插入订单
INSERT INTO orders (user_id, product_name, amount) VALUES (1, 'Test Product', 99.99);
```

### 2. 验证目标数据库

```sql
USE test_target;

-- 检查数据是否同步
SELECT * FROM users;
SELECT * FROM orders;
```

## 常见问题

### 1. 连接被拒绝 (Connection refused)

**问题**：`Communications link failure: Connection refused`

**解决方案**：
- 检查 MySQL 是否运行：`mysql -h 100.82.226.63 -P 31765 -u root -p`
- 检查防火墙设置
- 确认配置文件中的主机和端口正确

### 2. Binlog 文件未找到

**问题**：`Could not find first log file name in binary log index file`

**解决方案**：
- 确认 MySQL 已启用 binlog：`SHOW VARIABLES LIKE 'log_bin';`
- 检查 binlog 文件是否存在：`SHOW BINARY LOGS;`
- 重启 MySQL 服务以生成新的 binlog 文件

### 3. 获取 Binlog 位置失败

**问题**：`You have an error in your SQL syntax near 'MASTER STATUS'`

**原因**：
- MySQL 8.2+ 废弃了 `SHOW MASTER STATUS`，改用 `SHOW BINARY LOG STATUS`
- 用户缺少 `REPLICATION CLIENT` 权限

**解决方案**：
```sql
-- 授予 REPLICATION CLIENT 权限
GRANT REPLICATION CLIENT ON *.* TO 'root'@'%';
FLUSH PRIVILEGES;

-- 验证权限
SHOW GRANTS FOR 'root'@'%';

-- 测试命令（根据 MySQL 版本选择）
SHOW BINARY LOG STATUS;  -- MySQL 8.2+
SHOW MASTER STATUS;      -- MySQL 5.x - 8.1
```

**注意**：代码已自动处理版本兼容性，会先尝试新语法，失败后自动降级到旧语法。

### 3. 整数溢出错误

**问题**：`Value '4294967295' is outside of valid range for type java.lang.Integer`

**解决方案**：
- ✅ 已在最新版本中修复
- 如果仍然遇到，请更新到最新代码

### 4. 权限不足

**问题**：`Access denied for user 'root'@'%'`

**解决方案**：
```sql
-- 授予必要权限
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'root'@'%';
GRANT ALL PRIVILEGES ON test_target.* TO 'root'@'%';
FLUSH PRIVILEGES;
```

## 监控和调试

### 启用调试日志

修改 `docker/application.conf`：

```hocon
pekko {
  loglevel = "DEBUG"
}
```

### 查看 Binlog 位置

```sql
-- 查看当前 binlog 位置
SHOW MASTER STATUS;

-- 查看所有 binlog 文件
SHOW BINARY LOGS;

-- 查看 binlog 事件
SHOW BINLOG EVENTS IN 'mysql-bin.000001' LIMIT 10;
```

### 查看 CDC 偏移量

CDC 服务将偏移量存储在源数据库的 `cdc_offsets` 表中：

```sql
USE test;
SELECT * FROM cdc_offsets;
```

## 性能调优

### 1. 调整批处理大小

```hocon
cdc {
  parallelism {
    batch-size = 1000  # 增加批处理大小
    flush-interval = "5s"  # 调整刷新间隔
  }
}
```

### 2. 调整并行度

```hocon
cdc {
  parallelism {
    apply-worker-count = 16  # 增加工作线程
    partition-count = 128    # 增加分区数
  }
}
```

### 3. 调整连接池

```hocon
cdc {
  target {
    connection-pool {
      max-pool-size = 50  # 增加连接池大小
      min-idle = 10
    }
  }
}
```

## 停止服务

### 优雅停止

```bash
# 发送 SIGTERM 信号
kill -TERM <pid>

# 或使用 Ctrl+C
```

服务会：
1. 停止接收新的 binlog 事件
2. 完成当前批次的处理
3. 提交最后的偏移量
4. 关闭所有连接

## 下一步

- 阅读 [架构文档](ARCHITECTURE.md) 了解系统设计
- 阅读 [配置文档](CONFIGURATION.md) 了解详细配置选项
- 阅读 [操作文档](OPERATIONS.md) 了解运维指南
- 阅读 [故障排查](TROUBLESHOOTING.md) 了解常见问题解决方案

## 支持

如果遇到问题：
1. 检查日志文件
2. 查看 [故障排查文档](TROUBLESHOOTING.md)
3. 提交 Issue 并附上日志信息
