# 配置参数说明

本文档详细说明 MySQL CDC Service 的所有配置参数。

## 目录

- [数据库配置](#数据库配置)
- [CDC 处理配置](#cdc-处理配置)
- [监控配置](#监控配置)
- [性能调优](#性能调优)
- [高级配置](#高级配置)

## 数据库配置

### 源数据库配置

```hocon
source {
  mysql {
    # 数据库连接信息
    host = "localhost"              # MySQL 主机地址
    port = 3306                     # MySQL 端口
    username = "root"               # 用户名
    password = "password"           # 密码
    database = "source_db"          # 数据库名
    
    # Binlog 配置
    binlog {
      server-id = 1001              # CDC 服务的 Server ID（必须唯一）
      
      # 表过滤规则
      include-tables = []           # 包含的表（支持正则表达式）
      exclude-tables = []           # 排除的表（支持正则表达式）
      
      # 示例：
      # include-tables = ["users", "orders", "products"]
      # exclude-tables = ["logs.*", "temp_.*", "test_.*"]
    }
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `host` | String | localhost | MySQL 服务器地址 |
| `port` | Int | 3306 | MySQL 端口号 |
| `username` | String | root | 数据库用户名 |
| `password` | String | - | 数据库密码 |
| `database` | String | - | 数据库名称 |
| `server-id` | Int | 1001 | Binlog 复制的 Server ID，必须唯一 |
| `include-tables` | Array | [] | 需要同步的表列表，支持正则表达式 |
| `exclude-tables` | Array | [] | 需要排除的表列表，支持正则表达式 |

**注意事项**：
- `server-id` 必须在 MySQL 集群中唯一
- 表过滤规则支持正则表达式，如 `"user.*"` 匹配所有以 user 开头的表
- `include-tables` 和 `exclude-tables` 同时配置时，先应用 include 再应用 exclude

### 目标数据库配置

```hocon
target {
  mysql {
    # 数据库连接信息
    host = "localhost"
    port = 3307
    username = "root"
    password = "password"
    database = "target_db"
    
    # 连接池配置
    connection-pool {
      maximum-pool-size = 20        # 最大连接数
      minimum-idle = 5              # 最小空闲连接数
      connection-timeout = "30s"    # 连接超时时间
      idle-timeout = "10m"          # 空闲连接超时时间
      max-lifetime = "30m"          # 连接最大生命周期
    }
  }
}
```

**连接池参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `maximum-pool-size` | Int | 20 | 连接池最大连接数 |
| `minimum-idle` | Int | 5 | 最小空闲连接数 |
| `connection-timeout` | Duration | 30s | 获取连接的超时时间 |
| `idle-timeout` | Duration | 10m | 空闲连接的超时时间 |
| `max-lifetime` | Duration | 30m | 连接的最大生命周期 |

**调优建议**：
- 高并发场景：增加 `maximum-pool-size` 到 50-100
- 低延迟要求：增加 `minimum-idle` 保持足够的预热连接
- 长时间运行：适当降低 `max-lifetime` 避免连接泄漏

## CDC 处理配置

### 批处理配置

```hocon
cdc {
  batch {
    size = 1000                     # 批处理大小
    flush-interval = "5s"           # 刷新间隔
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `size` | Int | 1000 | 每批处理的事件数量 |
| `flush-interval` | Duration | 5s | 强制刷新的时间间隔 |

**调优建议**：
- 高吞吐场景：增加 `size` 到 2000-5000
- 低延迟要求：减少 `size` 到 100-500，减少 `flush-interval` 到 1-3s
- 大事务场景：适当增加 `size` 避免频繁刷新

### 并行处理配置

```hocon
cdc {
  parallelism {
    apply-workers = 4               # 并行工作线程数
    router-partitions = 16          # 路由分区数
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `apply-workers` | Int | 4 | 并行处理的工作线程数 |
| `router-partitions` | Int | 16 | 事件路由的分区数量 |

**调优建议**：
- CPU 核心数充足：设置 `apply-workers` = CPU 核心数
- 高并发场景：增加 `router-partitions` 到 32-64
- 保证顺序性：`router-partitions` 应该是 `apply-workers` 的倍数

### 偏移量管理配置

```hocon
cdc {
  offset {
    storage = "mysql"               # 存储类型：mysql 或 file
    commit-interval = "10s"         # 提交间隔
    
    # MySQL 存储配置
    mysql {
      table = "cdc_offsets"         # 偏移量表名
    }
    
    # 文件存储配置
    file {
      path = "/app/data/offsets"   # 偏移量文件路径
    }
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `storage` | String | mysql | 偏移量存储方式：mysql 或 file |
| `commit-interval` | Duration | 10s | 偏移量提交间隔 |
| `mysql.table` | String | cdc_offsets | MySQL 存储的表名 |
| `file.path` | String | /app/data/offsets | 文件存储的路径 |

**存储方式选择**：
- **MySQL 存储**：推荐用于生产环境，支持高可用和故障恢复
- **文件存储**：适合开发测试环境，简单快速

### 热表集配置

```hocon
cdc {
  hot-set {
    max-tables = 1000               # 最大热表数量
    min-residence-time = "5m"       # 最小驻留时间
    cooldown-time = "30m"           # 冷却时间
    temperature-threshold = 10.0    # 温度阈值
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `max-tables` | Int | 1000 | 热表集最大容量 |
| `min-residence-time` | Duration | 5m | 表进入热表集后的最小驻留时间 |
| `cooldown-time` | Duration | 30m | 表从热表集移除前的冷却时间 |
| `temperature-threshold` | Double | 10.0 | 表被认为是"热"的温度阈值 |

**调优建议**：
- 大规模场景（10万+ 表）：保持 `max-tables` = 1000-5000
- 活跃表较多：增加 `max-tables` 和减少 `cooldown-time`
- 表访问模式稳定：增加 `min-residence-time` 减少抖动

### 快照配置

```hocon
cdc {
  snapshot {
    max-concurrent-tasks = 2        # 最大并发快照任务数
    task-timeout-minutes = 120      # 任务超时时间（分钟）
    max-rows-per-chunk = 50000      # 每个分片的最大行数
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `max-concurrent-tasks` | Int | 2 | 同时执行的快照任务数 |
| `task-timeout-minutes` | Int | 120 | 单个快照任务的超时时间 |
| `max-rows-per-chunk` | Long | 50000 | 大表分片时每个分片的行数 |

**调优建议**：
- 资源充足：增加 `max-concurrent-tasks` 到 4-8
- 大表较多：减少 `max-rows-per-chunk` 到 10000-20000
- 快照时间长：增加 `task-timeout-minutes`

### DDL 处理配置

```hocon
cdc {
  ddl {
    strategy = "alert"              # 处理策略：ignore, log, alert, fail
    enable-alerts = true            # 是否启用告警
  }
}
```

**参数说明**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `strategy` | String | alert | DDL 处理策略 |
| `enable-alerts` | Boolean | true | 是否启用 DDL 告警 |

**策略说明**：
- **ignore**: 忽略 DDL 事件，继续处理
- **log**: 记录 DDL 事件到日志
- **alert**: 记录并发送告警（推荐）
- **fail**: 遇到 DDL 时停止服务

## 监控配置

### HTTP API 配置

```hocon
http {
  host = "0.0.0.0"                  # 监听地址
  port = 8080                       # 监听端口
}
```

### Prometheus 配置

```hocon
prometheus {
  host = "0.0.0.0"                  # 监听地址
  port = 9090                       # 监听端口
}
```

### 日志配置

```hocon
logging {
  level = "INFO"                    # 日志级别：DEBUG, INFO, WARN, ERROR
  structured = true                 # 是否使用结构化日志
  
  file {
    enabled = true                  # 是否启用文件日志
    path = "/app/logs"              # 日志文件路径
    max-file-size = "100MB"         # 单个日志文件最大大小
    max-history = 30                # 保留的历史文件数
  }
}
```

## 性能调优

### 高吞吐场景配置

```hocon
cdc {
  batch {
    size = 5000
    flush-interval = "3s"
  }
  
  parallelism {
    apply-workers = 8
    router-partitions = 32
  }
}

target {
  mysql {
    connection-pool {
      maximum-pool-size = 50
      minimum-idle = 10
    }
  }
}
```

### 低延迟场景配置

```hocon
cdc {
  batch {
    size = 100
    flush-interval = "1s"
  }
  
  parallelism {
    apply-workers = 4
    router-partitions = 16
  }
}
```

### 大规模表场景配置

```hocon
cdc {
  hot-set {
    max-tables = 5000
    min-residence-time = "10m"
    cooldown-time = "1h"
  }
  
  snapshot {
    max-concurrent-tasks = 4
    max-rows-per-chunk = 10000
  }
}
```

## 高级配置

### Pekko 配置

```hocon
pekko {
  loglevel = "INFO"
  
  actor {
    default-dispatcher {
      type = "Dispatcher"
      executor = "fork-join-executor"
      fork-join-executor {
        parallelism-min = 2
        parallelism-factor = 2.0
        parallelism-max = 64
      }
    }
  }
  
  stream {
    materializer {
      initial-input-buffer-size = 4
      max-input-buffer-size = 16
    }
  }
}
```

### JVM 参数

```bash
# 内存配置
-Xms2g -Xmx4g

# GC 配置
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:+UseStringDeduplication

# GC 日志
-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags
```

## 环境变量覆盖

所有配置参数都可以通过环境变量覆盖，格式为：

```bash
# 配置路径：source.mysql.host
export SOURCE_MYSQL_HOST=192.168.1.100

# 配置路径：cdc.batch.size
export CDC_BATCH_SIZE=2000

# 配置路径：logging.level
export LOG_LEVEL=DEBUG
```

## 配置验证

启动服务时会自动验证配置，常见错误：

1. **数据库连接失败**
   - 检查主机地址和端口
   - 确认用户名密码正确
   - 验证网络连通性

2. **Binlog 未启用**
   - 确认 MySQL 配置中启用了 Binlog
   - 检查 `binlog-format` 是否为 ROW

3. **权限不足**
   - 确保用户有 REPLICATION SLAVE 权限
   - 确保用户有目标表的读写权限

## 配置示例

完整的生产环境配置示例请参考：
- [开发环境配置](../docker/application.conf)
- [生产环境配置示例](./examples/production.conf)
- [高可用配置示例](./examples/ha.conf)