# 完整教程

本教程将带您从零开始，逐步学习如何使用 MySQL CDC Service 实现数据同步。

## 目录

- [环境准备](#环境准备)
- [快速入门](#快速入门)
- [基础配置](#基础配置)
- [进阶功能](#进阶功能)
- [生产部署](#生产部署)

## 环境准备

### 系统要求

- **操作系统**: Linux/macOS/Windows
- **Docker**: 20.10+
- **Docker Compose**: 1.29+
- **内存**: 最小 4GB
- **磁盘**: 最小 10GB 可用空间

### 安装 Docker

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install docker.io docker-compose
sudo systemctl start docker
sudo systemctl enable docker
```

**CentOS/RHEL:**
```bash
sudo yum install docker docker-compose
sudo systemctl start docker
sudo systemctl enable docker
```

**macOS:**
```bash
brew install docker docker-compose
```

### 验证安装

```bash
docker --version
docker-compose --version
docker run hello-world
```

## 快速入门

### 第一步：获取项目

```bash
# 克隆项目
git clone https://github.com/example/mysql-cdc-service.git
cd mysql-cdc-service

# 查看项目结构
tree -L 2
```

### 第二步：启动服务

```bash
# 启动所有服务
./scripts/deploy.sh start

# 查看服务状态
./scripts/deploy.sh status

# 查看日志
./scripts/deploy.sh logs cdc-service
```

启动过程包括：
1. 启动源 MySQL 数据库（端口 3306）
2. 启动目标 MySQL 数据库（端口 3307）
3. 初始化数据库结构
4. 启动 CDC 服务（端口 8080）
5. 启动监控服务（Prometheus + Grafana）

### 第三步：验证服务

```bash
# 检查健康状态
curl http://localhost:8080/health

# 预期输出：
# {
#   "status": "healthy",
#   "timestamp": "2024-01-01T12:00:00Z",
#   "uptime": "PT2M30S",
#   "version": "1.0.0"
# }

# 查看详细状态
curl http://localhost:8080/status | jq .
```

### 第四步：测试数据同步

#### 连接到源数据库

```bash
mysql -h localhost -P 3306 -u root -ppassword source_db
```

#### 插入测试数据

```sql
-- 查看现有数据
SELECT * FROM users;

-- 插入新用户
INSERT INTO users (username, email) VALUES 
('alice', 'alice@example.com'),
('bob', 'bob@example.com');

-- 更新用户
UPDATE users SET email = 'alice.smith@example.com' WHERE username = 'alice';

-- 删除用户
DELETE FROM users WHERE username = 'test';
```

#### 验证目标数据库

```bash
# 连接到目标数据库
mysql -h localhost -P 3307 -u root -ppassword target_db
```

```sql
-- 验证数据同步
SELECT * FROM users;

-- 应该看到与源数据库相同的数据
```

#### 监控同步状态

```bash
# 查看同步指标
curl http://localhost:8080/status | jq '{
  state: .cdc.state,
  ingestTPS: .cdc.ingestTPS,
  applyTPS: .cdc.applyTPS,
  binlogLag: .cdc.binlogLag
}'
```

## 基础配置

### 第五步：配置表过滤

#### 编辑配置文件

```bash
vim docker/application.conf
```

#### 添加表过滤规则

```hocon
source {
  mysql {
    binlog {
      # 只同步指定表
      include-tables = ["users", "orders", "products"]
      
      # 排除日志表
      exclude-tables = ["logs.*", "audit.*", "temp.*"]
    }
  }
}
```

#### 重启服务

```bash
./scripts/deploy.sh restart
```

#### 测试表过滤

```sql
-- 在源数据库创建测试表
CREATE TABLE logs_test (id INT PRIMARY KEY, message TEXT);
CREATE TABLE temp_data (id INT PRIMARY KEY, data TEXT);

-- 插入数据
INSERT INTO logs_test VALUES (1, 'test log');
INSERT INTO temp_data VALUES (1, 'temp data');

-- 这些数据不应该同步到目标数据库
```

### 第六步：性能调优

#### 高吞吐量配置

```hocon
cdc {
  batch {
    size = 5000              # 增加批处理大小
    flush-interval = "3s"    # 减少刷新间隔
  }
  
  parallelism {
    apply-workers = 8        # 增加并行工作线程
    router-partitions = 32   # 增加路由分区
  }
}

target {
  mysql {
    connection-pool {
      maximum-pool-size = 50 # 增加连接池大小
      minimum-idle = 10
    }
  }
}
```

#### 性能测试

```bash
# 创建性能测试脚本
cat > perf_test.sql << 'EOF'
DELIMITER //
CREATE PROCEDURE generate_test_data(IN num_records INT)
BEGIN
  DECLARE i INT DEFAULT 1;
  WHILE i <= num_records DO
    INSERT INTO users (username, email) VALUES 
    (CONCAT('user_', i), CONCAT('user_', i, '@example.com'));
    SET i = i + 1;
  END WHILE;
END//
DELIMITER ;

-- 生成 10000 条测试数据
CALL generate_test_data(10000);
EOF

# 执行性能测试
mysql -h localhost -P 3306 -u root -ppassword source_db < perf_test.sql

# 监控性能指标
watch -n 5 'curl -s http://localhost:8080/status | jq ".cdc.ingestTPS, .cdc.applyTPS"'
```

## 进阶功能

### 第七步：快照功能

#### 创建大表

```sql
-- 在源数据库创建大表
CREATE TABLE large_table (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  data VARCHAR(1000),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 插入大量数据（10万行）
INSERT INTO large_table (data)
SELECT CONCAT('data_', n)
FROM (
  SELECT a.N + b.N * 10 + c.N * 100 + d.N * 1000 + e.N * 10000 + 1 n
  FROM 
    (SELECT 0 AS N UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
    (SELECT 0 AS N UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b,
    (SELECT 0 AS N UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c,
    (SELECT 0 AS N UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d,
    (SELECT 0 AS N UNION ALL SELECT 1) e
) t
LIMIT 100000;
```

#### 启动快照

```bash
# 启动表快照
curl -X POST http://localhost:8080/api/snapshots \
  -H "Content-Type: application/json" \
  -d '{
    "tableId": "source_db.large_table",
    "options": {
      "chunkSize": 10000,
      "parallel": true
    }
  }'
```

#### 监控快照进度

```bash
# 查看快照状态
curl http://localhost:8080/api/snapshots | jq .

# 持续监控
watch -n 10 'curl -s http://localhost:8080/api/snapshots | jq ".[] | {id: .id, status: .status, progress: .progress}"'
```

### 第八步：DDL 处理

#### 配置 DDL 策略

```hocon
cdc {
  ddl {
    strategy = "alert"        # 遇到 DDL 时发送告警
    enable-alerts = true      # 启用告警
  }
}
```

#### 测试 DDL 处理

```sql
-- 在源数据库执行 DDL
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
```

#### 查看 DDL 告警

```bash
# 查看 DDL 告警
curl http://localhost:8080/api/ddl/alerts | jq .

# 查看 DDL 历史
curl http://localhost:8080/api/ddl/history | jq .
```

#### 手动处理 DDL

```sql
-- 在目标数据库执行相同的 DDL
mysql -h localhost -P 3307 -u root -ppassword target_db
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
```

### 第九步：监控和告警

#### 访问 Grafana

1. 打开浏览器访问 http://localhost:3000
2. 使用用户名/密码：admin/admin 登录
3. 查看 CDC 仪表板

#### 关键指标

- **cdc_events_ingested_total**: 接收事件总数
- **cdc_events_applied_total**: 应用事件总数
- **cdc_binlog_lag_seconds**: Binlog 延迟（秒）
- **cdc_ingest_rate_events_per_second**: 接收速率
- **cdc_apply_rate_events_per_second**: 应用速率
- **cdc_errors_total**: 错误总数

#### 设置告警

```yaml
# alerts.yml
groups:
  - name: cdc_alerts
    rules:
      - alert: HighBinlogLag
        expr: cdc_binlog_lag_seconds > 60
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Binlog lag is too high"
          description: "Binlog lag is {{ $value }} seconds"
```

## 生产部署

### 第十步：生产环境配置

#### 创建生产配置

```bash
cp docker/application.conf config/production.conf
vim config/production.conf
```

```hocon
# 生产环境配置
app {
  name = "mysql-cdc-service"
  version = "1.0.0"
  environment = "production"
}

# 使用环境变量
source {
  mysql {
    host = ${SOURCE_MYSQL_HOST}
    port = ${SOURCE_MYSQL_PORT}
    username = ${SOURCE_MYSQL_USERNAME}
    password = ${SOURCE_MYSQL_PASSWORD}
    database = ${SOURCE_MYSQL_DATABASE}
  }
}

target {
  mysql {
    host = ${TARGET_MYSQL_HOST}
    port = ${TARGET_MYSQL_PORT}
    username = ${TARGET_MYSQL_USERNAME}
    password = ${TARGET_MYSQL_PASSWORD}
    database = ${TARGET_MYSQL_DATABASE}
    
    connection-pool {
      maximum-pool-size = 100
      minimum-idle = 20
    }
  }
}

# 性能优化
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
    min-residence-time = "30m"
    cooldown-time = "2h"
  }
}

# 日志配置
logging {
  level = "INFO"
  structured = true
  file {
    enabled = true
    path = "/app/logs"
  }
}
```

#### 创建环境变量文件

```bash
cat > .env.prod << 'EOF'
# 源数据库
SOURCE_MYSQL_HOST=source-db.example.com
SOURCE_MYSQL_PORT=3306
SOURCE_MYSQL_USERNAME=cdc_user
SOURCE_MYSQL_PASSWORD=secure_password
SOURCE_MYSQL_DATABASE=source_db

# 目标数据库
TARGET_MYSQL_HOST=target-db.example.com
TARGET_MYSQL_PORT=3306
TARGET_MYSQL_USERNAME=cdc_user
TARGET_MYSQL_PASSWORD=secure_password
TARGET_MYSQL_DATABASE=target_db

# 性能配置
CDC_BATCH_SIZE=5000
CDC_APPLY_WORKERS=16
CDC_ROUTER_PARTITIONS=64

# JVM 配置
JAVA_OPTS=-Xms4g -Xmx8g -XX:+UseG1GC

# 日志配置
LOG_LEVEL=INFO
LOG_STRUCTURED=true
EOF
```

### 第十一步：构建和部署

#### 构建生产镜像

```bash
# 构建镜像
./scripts/build.sh docker 1.0.0

# 推送到镜像仓库
./scripts/build.sh push 1.0.0 registry.example.com
```

#### 部署到生产服务器

```bash
# 1. 在生产服务器创建目录
ssh production-server
mkdir -p /opt/cdc
cd /opt/cdc

# 2. 复制配置文件
scp docker-compose.prod.yml production-server:/opt/cdc/
scp .env.prod production-server:/opt/cdc/.env

# 3. 拉取镜像
docker pull registry.example.com/mysql-cdc-service:1.0.0

# 4. 启动服务
docker-compose -f docker-compose.prod.yml up -d

# 5. 验证部署
curl http://localhost:8080/health
```

### 第十二步：备份和恢复

#### 设置自动备份

```bash
# 创建备份脚本
cat > /opt/cdc/backup.sh << 'EOF'
#!/bin/bash

BACKUP_DIR="/backup/cdc/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

# 备份配置
cp -r /opt/cdc/config "$BACKUP_DIR/"
cp /opt/cdc/docker-compose.prod.yml "$BACKUP_DIR/"

# 备份偏移量数据
docker exec mysql-cdc-service-prod \
  mysqldump -h target-mysql -u root -p$TARGET_MYSQL_PASSWORD \
  $TARGET_MYSQL_DATABASE cdc_offsets > "$BACKUP_DIR/offsets.sql"

# 压缩备份
tar -czf "$BACKUP_DIR.tar.gz" -C "$(dirname $BACKUP_DIR)" "$(basename $BACKUP_DIR)"
rm -rf "$BACKUP_DIR"

# 清理旧备份（保留 30 天）
find /backup/cdc -name "*.tar.gz" -mtime +30 -delete

echo "Backup completed: $BACKUP_DIR.tar.gz"
EOF

chmod +x /opt/cdc/backup.sh

# 设置定时备份（每天凌晨 2 点）
crontab -e
# 添加：0 2 * * * /opt/cdc/backup.sh >> /var/log/cdc-backup.log 2>&1
```

#### 恢复流程

```bash
# 创建恢复脚本
cat > /opt/cdc/restore.sh << 'EOF'
#!/bin/bash

BACKUP_FILE=$1

if [ -z "$BACKUP_FILE" ]; then
    echo "Usage: $0 <backup_file.tar.gz>"
    exit 1
fi

# 停止服务
docker-compose -f docker-compose.prod.yml down

# 解压备份
TEMP_DIR="/tmp/cdc_restore_$(date +%s)"
mkdir -p "$TEMP_DIR"
tar -xzf "$BACKUP_FILE" -C "$TEMP_DIR"

# 恢复配置
cp -r "$TEMP_DIR"/*/config/* /opt/cdc/config/
cp "$TEMP_DIR"/*/docker-compose.prod.yml /opt/cdc/

# 恢复偏移量数据
if [ -f "$TEMP_DIR"/*/offsets.sql ]; then
    mysql -h $TARGET_MYSQL_HOST -u $TARGET_MYSQL_USERNAME -p$TARGET_MYSQL_PASSWORD \
      $TARGET_MYSQL_DATABASE < "$TEMP_DIR"/*/offsets.sql
fi

# 清理临时文件
rm -rf "$TEMP_DIR"

# 重启服务
docker-compose -f docker-compose.prod.yml up -d

echo "Restore completed from $BACKUP_FILE"
EOF

chmod +x /opt/cdc/restore.sh
```

### 第十三步：监控和运维

#### 设置健康检查

```bash
# 创建健康检查脚本
cat > /opt/cdc/health_check.sh << 'EOF'
#!/bin/bash

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
EOF

chmod +x /opt/cdc/health_check.sh

# 设置定时检查（每 5 分钟）
crontab -e
# 添加：*/5 * * * * /opt/cdc/health_check.sh
```

#### 设置告警通知

```yaml
# alertmanager.yml
route:
  receiver: 'default'
  group_by: ['alertname', 'severity']
  routes:
    - match:
        severity: critical
      receiver: 'critical-alerts'
    - match:
        severity: warning
      receiver: 'warning-alerts'

receivers:
  - name: 'default'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/xxx'
        channel: '#data-platform'
        title: 'CDC Alert'
  
  - name: 'critical-alerts'
    email_configs:
      - to: 'oncall@example.com'
        from: 'alerts@example.com'
        subject: 'CRITICAL: CDC Alert'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/xxx'
        channel: '#incidents'
        title: 'CRITICAL CDC Alert'
  
  - name: 'warning-alerts'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/xxx'
        channel: '#data-platform'
        title: 'WARNING: CDC Alert'
```

## 故障处理

### 常见问题

#### 问题 1: 服务无法启动

```bash
# 检查日志
docker logs mysql-cdc-service

# 检查配置
docker exec mysql-cdc-service cat /app/config/application.conf

# 检查数据库连接
mysql -h $SOURCE_MYSQL_HOST -u $SOURCE_MYSQL_USERNAME -p
```

#### 问题 2: 数据同步延迟

```bash
# 检查延迟
curl http://localhost:9090/metrics | grep cdc_binlog_lag_seconds

# 检查 TPS
curl http://localhost:8080/status | jq '.cdc.ingestTPS, .cdc.applyTPS'

# 临时增加并行度
vim docker/application.conf
# 增加 apply-workers 和 router-partitions
./scripts/deploy.sh restart
```

#### 问题 3: 内存溢出

```bash
# 检查内存使用
docker stats mysql-cdc-service

# 生成堆转储
docker exec mysql-cdc-service jmap -dump:format=b,file=/tmp/heap.bin 1

# 临时增加内存
# 修改 docker-compose.yml 中的 JAVA_OPTS
JAVA_OPTS: "-Xms4g -Xmx8g"
```

## 总结

通过本教程，您已经学会了：

1. ✓ 安装和配置 MySQL CDC Service
2. ✓ 测试基本的数据同步功能
3. ✓ 配置表过滤和性能优化
4. ✓ 使用快照和 DDL 处理功能
5. ✓ 部署到生产环境
6. ✓ 设置监控和告警
7. ✓ 处理常见故障

## 下一步

- 阅读 [架构设计文档](ARCHITECTURE.md) 了解系统原理
- 查看 [配置参数说明](CONFIGURATION.md) 进行深度定制
- 参考 [运维手册](OPERATIONS.md) 学习日常运维
- 查阅 [故障排查手册](TROUBLESHOOTING.md) 解决问题

## 获取帮助

- 📧 邮件支持: support@example.com
- 📖 文档: https://docs.example.com
- 🐛 问题反馈: https://github.com/example/mysql-cdc-service/issues
