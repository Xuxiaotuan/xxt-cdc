# Connector 配置指南

## 概述

CDC 系统的 Connector 架构支持灵活的配置方式，每个 Connector 可以有自己特定的配置选项。

## 配置结构

### 基础配置

```hocon
cdc {
  task-name = "my-cdc-task"
  
  # Connector 类型
  source-type = "mysql"      # 源数据库类型
  target-type = "starrocks"  # 目标数据库类型
  
  # 数据库连接配置
  source { ... }
  target { ... }
  metadata { ... }
  
  # 通用配置
  filter { ... }
  parallelism { ... }
  offset { ... }
  
  # Connector 特定配置
  source-connector-config {
    # Source Connector 特定选项
  }
  
  sink-connector-config {
    # Sink Connector 特定选项
  }
}
```

## MySQL Source Connector 配置

### 基础配置

```hocon
cdc {
  source-type = "mysql"
  
  source {
    host = "mysql-host"
    port = 3306
    username = "root"
    password = "password"
    database = "source_db"
    
    connection-pool {
      max-pool-size = 10
      min-idle = 2
      connection-timeout = 30s
    }
  }
  
  # MySQL 特定配置
  source-connector-config {
    # JDBC 连接参数
    jdbc.useSSL = "false"
    jdbc.serverTimezone = "UTC"
    jdbc.characterEncoding = "utf8mb4"
    
    # Binlog 读取配置
    binlog.keepAlive = "true"
    binlog.keepAliveInterval = "60000"
    binlog.connectTimeout = "30000"
    
    # 数据源属性
    datasource.cachePrepStmts = "true"
    datasource.prepStmtCacheSize = "250"
  }
}
```

### 高级配置选项

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| `jdbc.useSSL` | 是否使用 SSL 连接 | `false` |
| `jdbc.serverTimezone` | 服务器时区 | `UTC` |
| `jdbc.characterEncoding` | 字符编码 | `utf8mb4` |
| `jdbc.rewriteBatchedStatements` | 批量语句重写 | `true` |
| `binlog.keepAlive` | 启用心跳检测 | `true` |
| `binlog.keepAliveInterval` | 心跳间隔（毫秒） | `60000` |
| `binlog.connectTimeout` | 连接超时（毫秒） | `30000` |
| `datasource.cachePrepStmts` | 缓存 PreparedStatement | `true` |
| `datasource.prepStmtCacheSize` | PreparedStatement 缓存大小 | `250` |

## MySQL Sink Connector 配置

### 基础配置

```hocon
cdc {
  target-type = "mysql"
  
  target {
    host = "mysql-target"
    port = 3306
    username = "root"
    password = "password"
    database = "target_db"
    
    connection-pool {
      max-pool-size = 20
      min-idle = 5
      connection-timeout = 30s
    }
  }
  
  # MySQL 特定配置
  sink-connector-config {
    # JDBC 连接参数
    jdbc.useSSL = "false"
    jdbc.rewriteBatchedStatements = "true"
    
    # 写入优化
    datasource.cachePrepStmts = "true"
    datasource.prepStmtCacheSize = "250"
    datasource.useServerPrepStmts = "true"
  }
}
```

## StarRocks Sink Connector 配置

### 基础配置

```hocon
cdc {
  target-type = "starrocks"
  
  target {
    host = "starrocks-fe"
    port = 9030  # FE MySQL 协议端口
    username = "root"
    password = "password"
    database = "target_db"
    
    connection-pool {
      max-pool-size = 50  # StarRocks 可以处理更高并发
      min-idle = 10
      connection-timeout = 30s
    }
  }
  
  # StarRocks 特定配置
  sink-connector-config {
    # JDBC 连接参数
    jdbc.useSSL = "false"
    jdbc.serverTimezone = "UTC"
    
    # Stream Load 配置（未来支持）
    # streamLoad.url = "http://starrocks-fe:8030"
    # streamLoad.batchSize = "10000"
    # streamLoad.maxRetries = "3"
  }
}
```

### 高级配置选项

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| `jdbc.useSSL` | 是否使用 SSL 连接 | `false` |
| `jdbc.serverTimezone` | 服务器时区 | `UTC` |
| `streamLoad.url` | Stream Load API 地址 | - |
| `streamLoad.batchSize` | Stream Load 批次大小 | `10000` |
| `streamLoad.maxRetries` | 最大重试次数 | `3` |

## PostgreSQL Source Connector 配置（未来）

```hocon
cdc {
  source-type = "postgresql"
  
  source {
    host = "pg-host"
    port = 5432
    username = "postgres"
    password = "password"
    database = "source_db"
    
    connection-pool {
      max-pool-size = 10
      min-idle = 2
      connection-timeout = 30s
    }
  }
  
  # PostgreSQL 特定配置
  source-connector-config {
    # JDBC 连接参数
    jdbc.ssl = "false"
    jdbc.sslMode = "disable"
    
    # 逻辑复制配置
    replication.slotName = "cdc_slot"
    replication.publicationName = "cdc_publication"
    replication.pluginName = "pgoutput"
    
    # WAL 读取配置
    wal.statusInterval = "10000"
    wal.maxRetries = "3"
  }
}
```

## 完整配置示例

### MySQL → StarRocks（生产环境）

```hocon
cdc {
  task-name = "prod-mysql-to-starrocks"
  source-type = "mysql"
  target-type = "starrocks"
  
  # 源数据库配置
  source {
    host = "mysql-master.prod.internal"
    port = 3306
    username = "cdc_user"
    password = ${CDC_SOURCE_PASSWORD}  # 从环境变量读取
    database = "production"
    
    connection-pool {
      max-pool-size = 20
      min-idle = 5
      connection-timeout = 30s
    }
  }
  
  # 目标数据库配置
  target {
    host = "starrocks-fe.prod.internal"
    port = 9030
    username = "root"
    password = ${CDC_TARGET_PASSWORD}
    database = "production_dw"
    
    connection-pool {
      max-pool-size = 50
      min-idle = 10
      connection-timeout = 30s
    }
  }
  
  # 元数据库配置
  metadata {
    host = "mysql-metadata.prod.internal"
    port = 3306
    username = "cdc_meta"
    password = ${CDC_META_PASSWORD}
    database = "cdc_metadata"
    
    connection-pool {
      max-pool-size = 10
      min-idle = 2
      connection-timeout = 30s
    }
  }
  
  # 表过滤配置
  filter {
    include-databases = ["production"]
    exclude-databases = []
    include-table-patterns = ["*"]
    exclude-table-patterns = ["*_log", "*_tmp", "*_audit"]
  }
  
  # 并行度配置
  parallelism {
    partition-count = 256
    apply-worker-count = 32
    snapshot-worker-count = 16
    batch-size = 1000
    flush-interval = 5s
  }
  
  # 偏移量配置
  offset {
    store-type = "mysql"
    commit-interval = 30s
    start-from-latest = false
    enable-snapshot = true
  }
  
  # MySQL Source 特定配置
  source-connector-config {
    # JDBC 优化
    jdbc.useSSL = "true"
    jdbc.serverTimezone = "Asia/Shanghai"
    jdbc.characterEncoding = "utf8mb4"
    jdbc.rewriteBatchedStatements = "true"
    
    # Binlog 配置
    binlog.keepAlive = "true"
    binlog.keepAliveInterval = "60000"
    binlog.connectTimeout = "30000"
    
    # 性能优化
    datasource.cachePrepStmts = "true"
    datasource.prepStmtCacheSize = "500"
    datasource.prepStmtCacheSqlLimit = "4096"
    datasource.useServerPrepStmts = "true"
  }
  
  # StarRocks Sink 特定配置
  sink-connector-config {
    # JDBC 配置
    jdbc.useSSL = "false"
    jdbc.serverTimezone = "Asia/Shanghai"
    
    # 连接测试
    connectionTestQuery = "SELECT 1"
  }
}
```

### MySQL → MySQL（跨数据中心同步）

```hocon
cdc {
  task-name = "dc-replication"
  source-type = "mysql"
  target-type = "mysql"
  
  source {
    host = "mysql-dc1.internal"
    port = 3306
    username = "repl_user"
    password = ${REPL_PASSWORD}
    database = "app_db"
    
    connection-pool {
      max-pool-size = 10
      min-idle = 2
      connection-timeout = 30s
    }
  }
  
  target {
    host = "mysql-dc2.internal"
    port = 3306
    username = "repl_user"
    password = ${REPL_PASSWORD}
    database = "app_db"
    
    connection-pool {
      max-pool-size = 20
      min-idle = 5
      connection-timeout = 30s
    }
  }
  
  metadata {
    host = "mysql-dc1.internal"
    port = 3306
    username = "cdc_meta"
    password = ${META_PASSWORD}
    database = "cdc_metadata"
    
    connection-pool {
      max-pool-size = 5
      min-idle = 1
      connection-timeout = 30s
    }
  }
  
  filter {
    include-databases = ["app_db"]
    exclude-databases = []
    include-table-patterns = ["*"]
    exclude-table-patterns = []
  }
  
  parallelism {
    partition-count = 64
    apply-worker-count = 8
    snapshot-worker-count = 4
    batch-size = 100
    flush-interval = 1s
  }
  
  offset {
    store-type = "mysql"
    commit-interval = 5s
    start-from-latest = false
    enable-snapshot = true
  }
  
  # 跨数据中心优化
  source-connector-config {
    jdbc.useSSL = "true"
    jdbc.tcpKeepAlive = "true"
    binlog.keepAlive = "true"
    binlog.keepAliveInterval = "30000"
  }
  
  sink-connector-config {
    jdbc.useSSL = "true"
    jdbc.tcpKeepAlive = "true"
    jdbc.rewriteBatchedStatements = "true"
  }
}
```

## 配置最佳实践

### 1. 连接池配置

```hocon
connection-pool {
  # 根据并发度设置
  max-pool-size = ${parallelism.apply-worker-count} * 2
  min-idle = ${parallelism.apply-worker-count} / 2
  connection-timeout = 30s
}
```

### 2. 性能优化

**高吞吐场景**:
```hocon
parallelism {
  partition-count = 256
  apply-worker-count = 32
  batch-size = 1000
  flush-interval = 5s
}

sink-connector-config {
  jdbc.rewriteBatchedStatements = "true"
  datasource.cachePrepStmts = "true"
}
```

**低延迟场景**:
```hocon
parallelism {
  partition-count = 64
  apply-worker-count = 16
  batch-size = 50
  flush-interval = 500ms
}

offset {
  commit-interval = 1s
}
```

### 3. 安全配置

```hocon
source {
  password = ${CDC_SOURCE_PASSWORD}  # 从环境变量读取
}

source-connector-config {
  jdbc.useSSL = "true"
  jdbc.requireSSL = "true"
  jdbc.verifyServerCertificate = "true"
}
```

### 4. 监控配置

```hocon
source-connector-config {
  # 启用连接测试
  connectionTestQuery = "SELECT 1"
  
  # 心跳检测
  binlog.keepAlive = "true"
  binlog.keepAliveInterval = "60000"
}
```

## 故障排查

### 连接问题

如果遇到连接超时：
```hocon
connection-pool {
  connection-timeout = 60s  # 增加超时时间
}

source-connector-config {
  binlog.connectTimeout = "60000"
}
```

### 性能问题

如果吞吐量不足：
```hocon
parallelism {
  partition-count = 512  # 增加分区数
  apply-worker-count = 64  # 增加 worker 数
}

connection-pool {
  max-pool-size = 100  # 增加连接池大小
}

sink-connector-config {
  jdbc.rewriteBatchedStatements = "true"  # 启用批量重写
}
```

### 内存问题

如果内存占用过高：
```hocon
parallelism {
  batch-size = 50  # 减小批次大小
  flush-interval = 500ms  # 减小刷新间隔
}

connection-pool {
  max-pool-size = 20  # 减小连接池大小
}
```

## 参考资料

- [Connector 架构文档](./CONNECTOR_ARCHITECTURE.md)
- [配置示例](./connector-examples.conf)
- [HikariCP 配置](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
