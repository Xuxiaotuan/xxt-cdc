# 使用示例

本文档提供 MySQL CDC Service 的实用示例和最佳实践。

## 目录

- [基础示例](#基础示例)
- [配置示例](#配置示例)
- [高级用法](#高级用法)
- [常见场景](#常见场景)

## 基础示例

### 示例 1: 单表同步

最简单的使用场景，同步单个表的数据变更。

```hocon
# docker/application.conf
source {
  mysql {
    host = "localhost"
    port = 3306
    username = "root"
    password = "password"
    database = "source_db"
    
    binlog {
      server-id = 1001
      include-tables = ["users"]
    }
  }
}

target {
  mysql {
    host = "localhost"
    port = 3307
    username = "root"
    password = "password"
    database = "target_db"
  }
}
```

测试数据同步：

```sql
-- 在源数据库插入数据
INSERT INTO users (username, email) VALUES ('alice', 'alice@example.com');

-- 在目标数据库验证
SELECT * FROM users WHERE username = 'alice';
```

### 示例 2: 多表同步

同步多个相关表的数据。

```hocon
source {
  mysql {
    binlog {
      include-tables = ["users", "orders", "products"]
    }
  }
}
```

### 示例 3: 使用正则表达式过滤

使用正则表达式匹配多个表。

```hocon
source {
  mysql {
    binlog {
      # 包含所有以 user_ 开头的表
      include-tables = ["user_.*"]
      
      # 排除所有日志和临时表
      exclude-tables = ["log_.*", "temp_.*", "test_.*"]
    }
  }
}
```

## 配置示例

### 示例 4: 高性能配置

适用于高吞吐量场景：

```hocon
cdc {
  batch {
    size = 5000
    flush-interval = "3s"
  }
  
  parallelism {
    apply-workers = 16
    router-partitions = 64
  }
  
  hot-set {
    max-tables = 5000
  }
}

target {
  mysql {
    connection-pool {
      maximum-pool-size = 100
      minimum-idle = 20
    }
  }
}
```

### 示例 5: 低延迟配置

适用于实时性要求高的场景：

```hocon
cdc {
  batch {
    size = 100
    flush-interval = "500ms"
  }
  
  parallelism {
    apply-workers = 4
    router-partitions = 16
  }
  
  offset {
    commit-interval = "3s"
  }
}
```

### 示例 6: 大规模表配置

适用于 10万+ 表的场景：

```hocon
cdc {
  hot-set {
    max-tables = 10000
    min-residence-time = "30m"
    cooldown-time = "4h"
    temperature-threshold = 5.0
  }
  
  snapshot {
    max-concurrent-tasks = 8
    max-rows-per-chunk = 10000
  }
}
```

## 高级用法

### 示例 7: 监控脚本

自动化监控脚本：

```bash
#!/bin/bash
# monitor.sh

# 检查服务健康状态
check_health() {
  HEALTH=$(curl -s http://localhost:8080/health | jq -r '.status')
  if [ "$HEALTH" != "healthy" ]; then
    echo "WARNING: Service is unhealthy"
    return 1
  fi
  return 0
}

# 检查 Binlog 延迟
check_lag() {
  LAG=$(curl -s http://localhost:9090/metrics | grep cdc_binlog_lag_seconds | awk '{print $2}')
  if (( $(echo "$LAG > 60" | bc -l) )); then
    echo "WARNING: Binlog lag is high: ${LAG}s"
    return 1
  fi
  return 0
}

# 检查错误率
check_errors() {
  ERRORS=$(curl -s http://localhost:9090/metrics | grep cdc_errors_total | awk '{print $2}')
  if [ "$ERRORS" -gt 100 ]; then
    echo "WARNING: Error count is high: $ERRORS"
    return 1
  fi
  return 0
}

# 主循环
while true; do
  echo "=== $(date) ==="
  check_health && echo "✓ Health OK"
  check_lag && echo "✓ Lag OK"
  check_errors && echo "✓ Errors OK"
  echo ""
  sleep 60
done
```

### 示例 8: 性能测试脚本

生成测试数据并监控性能：

```bash
#!/bin/bash
# perf_test.sh

# 生成测试数据
generate_data() {
  local count=$1
  mysql -h localhost -P 3306 -u root -ppassword source_db << EOF
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS generate_test_data(IN num_records INT)
BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= num_records DO
    INSERT INTO users (username, email) VALUES 
    (CONCAT('user_', i), CONCAT('user_', i, '@example.com'));
    SET i = i + 1;
  END WHILE;
END//
DELIMITER ;

CALL generate_test_data($count);
EOF
}

# 监控性能
monitor_performance() {
  local duration=$1
  local interval=5
  local iterations=$((duration / interval))
  
  echo "Monitoring performance for ${duration}s..."
  for i in $(seq 1 $iterations); do
    STATUS=$(curl -s http://localhost:8080/status)
    INGEST_TPS=$(echo $STATUS | jq -r '.cdc.ingestTPS')
    APPLY_TPS=$(echo $STATUS | jq -r '.cdc.applyTPS')
    LAG=$(curl -s http://localhost:9090/metrics | grep cdc_binlog_lag_seconds | awk '{print $2}')
    
    echo "$(date): Ingest TPS: $INGEST_TPS, Apply TPS: $APPLY_TPS, Lag: ${LAG}s"
    sleep $interval
  done
}

# 执行测试
echo "Starting performance test..."
generate_data 50000
monitor_performance 300
echo "Test completed"
```

### 示例 9: 数据一致性验证

验证源和目标数据库的一致性：

```bash
#!/bin/bash
# verify_consistency.sh

SOURCE_HOST="localhost"
SOURCE_PORT="3306"
TARGET_HOST="localhost"
TARGET_PORT="3307"
DB_USER="root"
DB_PASS="password"
DATABASE="source_db"

# 检查表行数
check_row_count() {
  local table=$1
  
  SOURCE_COUNT=$(mysql -h $SOURCE_HOST -P $SOURCE_PORT -u $DB_USER -p$DB_PASS $DATABASE \
    -sN -e "SELECT COUNT(*) FROM $table")
  
  TARGET_COUNT=$(mysql -h $TARGET_HOST -P $TARGET_PORT -u $DB_USER -p$DB_PASS $DATABASE \
    -sN -e "SELECT COUNT(*) FROM $table")
  
  if [ "$SOURCE_COUNT" -eq "$TARGET_COUNT" ]; then
    echo "✓ $table: $SOURCE_COUNT rows (consistent)"
  else
    echo "✗ $table: Source=$SOURCE_COUNT, Target=$TARGET_COUNT (inconsistent)"
    return 1
  fi
}

# 检查数据校验和
check_checksum() {
  local table=$1
  
  SOURCE_CHECKSUM=$(mysql -h $SOURCE_HOST -P $SOURCE_PORT -u $DB_USER -p$DB_PASS $DATABASE \
    -sN -e "CHECKSUM TABLE $table" | awk '{print $2}')
  
  TARGET_CHECKSUM=$(mysql -h $TARGET_HOST -P $TARGET_PORT -u $DB_USER -p$DB_PASS $DATABASE \
    -sN -e "CHECKSUM TABLE $table" | awk '{print $2}')
  
  if [ "$SOURCE_CHECKSUM" -eq "$TARGET_CHECKSUM" ]; then
    echo "✓ $table: Checksum matches"
  else
    echo "✗ $table: Checksum mismatch"
    return 1
  fi
}

# 获取所有表
TABLES=$(mysql -h $SOURCE_HOST -P $SOURCE_PORT -u $DB_USER -p$DB_PASS $DATABASE \
  -sN -e "SHOW TABLES")

# 验证每个表
echo "=== Consistency Verification ==="
for table in $TABLES; do
  check_row_count $table
  check_checksum $table
done
```

## 常见场景

### 场景 1: 数据仓库同步

将业务数据库同步到数据仓库：

```hocon
# 配置
cdc {
  batch {
    size = 10000  # 大批处理
    flush-interval = "30s"
  }
  
  parallelism {
    apply-workers = 8
    router-partitions = 32
  }
}

source {
  mysql {
    binlog {
      # 只同步业务表
      include-tables = ["business_.*", "fact_.*", "dim_.*"]
      exclude-tables = ["log_.*", "temp_.*"]
    }
  }
}
```

### 场景 2: 实时分析

为实时分析系统提供数据：

```hocon
# 配置
cdc {
  batch {
    size = 100  # 小批处理，低延迟
    flush-interval = "1s"
  }
  
  hot-set {
    min-residence-time = "30s"
    cooldown-time = "5m"
  }
}

source {
  mysql {
    binlog {
      # 只同步关键事件表
      include-tables = ["events", "user_actions", "transactions"]
    }
  }
}
```

### 场景 3: 灾备同步

主备数据库同步：

```hocon
# 配置
cdc {
  batch {
    size = 5000
    flush-interval = "10s"
  }
  
  offset {
    storage = "mysql"  # 使用 MySQL 存储偏移量
    commit-interval = "5s"  # 频繁提交，确保一致性
  }
}

source {
  mysql {
    binlog {
      # 同步所有表
      include-tables = [".*"]
      exclude-tables = ["information_schema.*", "performance_schema.*"]
    }
  }
}
```

### 场景 4: 微服务数据同步

在微服务之间同步数据：

```hocon
# 服务 A 到服务 B
source {
  mysql {
    database = "service_a_db"
    binlog {
      include-tables = ["shared_.*"]
    }
  }
}

target {
  mysql {
    database = "service_b_db"
  }
}

cdc {
  parallelism {
    apply-workers = 4
    router-partitions = 16
  }
}
```

### 场景 5: 读写分离

主库写入，从库读取：

```hocon
# 配置
cdc {
  batch {
    size = 2000
    flush-interval = "5s"
  }
  
  # 确保低延迟
  offset {
    commit-interval = "3s"
  }
}

# 监控延迟
# 确保 cdc_binlog_lag_seconds < 5
```

## 故障处理示例

### 示例 10: 自动重启脚本

服务异常时自动重启：

```bash
#!/bin/bash
# auto_restart.sh

MAX_RETRIES=3
RETRY_COUNT=0

while true; do
  # 检查服务健康状态
  HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health)
  
  if [ "$HEALTH" != "200" ]; then
    echo "$(date): Service unhealthy, restarting..."
    
    # 重启服务
    ./scripts/deploy.sh restart
    
    # 等待服务启动
    sleep 30
    
    # 验证重启
    HEALTH=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health)
    if [ "$HEALTH" == "200" ]; then
      echo "$(date): Service restarted successfully"
      RETRY_COUNT=0
    else
      RETRY_COUNT=$((RETRY_COUNT + 1))
      echo "$(date): Restart failed, retry count: $RETRY_COUNT"
      
      if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
        echo "$(date): Max retries reached, sending alert..."
        # 发送告警
        RETRY_COUNT=0
      fi
    fi
  fi
  
  sleep 60
done
```

### 示例 11: 延迟告警脚本

Binlog 延迟过高时发送告警：

```bash
#!/bin/bash
# lag_alert.sh

THRESHOLD=60  # 延迟阈值（秒）
ALERT_SENT=false

while true; do
  LAG=$(curl -s http://localhost:9090/metrics | grep cdc_binlog_lag_seconds | awk '{print $2}')
  
  if (( $(echo "$LAG > $THRESHOLD" | bc -l) )); then
    if [ "$ALERT_SENT" = false ]; then
      echo "$(date): ALERT - Binlog lag is high: ${LAG}s"
      
      # 发送告警（示例：发送邮件）
      echo "Binlog lag is ${LAG}s, exceeds threshold ${THRESHOLD}s" | \
        mail -s "CDC Alert: High Binlog Lag" ops-team@example.com
      
      ALERT_SENT=true
    fi
  else
    if [ "$ALERT_SENT" = true ]; then
      echo "$(date): Binlog lag recovered: ${LAG}s"
      ALERT_SENT=false
    fi
  fi
  
  sleep 60
done
```

## 最佳实践

### 实践 1: 渐进式部署

```bash
#!/bin/bash
# gradual_deployment.sh

# 1. 先同步少量表进行验证
echo "Phase 1: Testing with 10 tables..."
# 修改配置只包含 10 个表
./scripts/deploy.sh restart
sleep 300  # 运行 5 分钟

# 2. 验证数据一致性
./verify_consistency.sh

# 3. 逐步增加表数量
echo "Phase 2: Adding more tables..."
# 修改配置增加到 100 个表
./scripts/deploy.sh restart
sleep 600  # 运行 10 分钟

# 4. 最终全量同步
echo "Phase 3: Full deployment..."
# 修改配置包含所有表
./scripts/deploy.sh restart
```

### 实践 2: 定期健康检查

```bash
#!/bin/bash
# health_check.sh

# 添加到 crontab
# */5 * * * * /path/to/health_check.sh

LOG_FILE="/var/log/cdc-health.log"

{
  echo "=== $(date) ==="
  
  # 检查服务状态
  curl -s http://localhost:8080/health | jq .
  
  # 检查关键指标
  curl -s http://localhost:8080/status | jq '{
    state: .cdc.state,
    ingestTPS: .cdc.ingestTPS,
    applyTPS: .cdc.applyTPS,
    binlogLag: .cdc.binlogLag,
    queueDepth: .cdc.queueDepth
  }'
  
  # 检查错误
  ERRORS=$(curl -s http://localhost:9090/metrics | grep cdc_errors_total | awk '{print $2}')
  echo "Total Errors: $ERRORS"
  
  echo ""
} >> $LOG_FILE
```

### 实践 3: 配置版本管理

```bash
#!/bin/bash
# config_backup.sh

BACKUP_DIR="/backup/cdc/configs"
DATE=$(date +%Y%m%d_%H%M%S)

# 备份当前配置
mkdir -p "$BACKUP_DIR/$DATE"
cp docker/application.conf "$BACKUP_DIR/$DATE/"
cp docker-compose.yml "$BACKUP_DIR/$DATE/"

# 记录变更
git add docker/application.conf docker-compose.yml
git commit -m "Config update: $DATE"

echo "Configuration backed up to $BACKUP_DIR/$DATE"
```

这些示例涵盖了 MySQL CDC Service 的主要使用场景和最佳实践。根据具体需求选择合适的配置和脚本。
