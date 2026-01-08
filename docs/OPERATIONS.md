# 运维操作手册

本文档提供 MySQL CDC Service 的日常运维操作指南。

## 目录

- [日常运维](#日常运维)
- [部署操作](#部署操作)
- [监控检查](#监控检查)
- [备份恢复](#备份恢复)
- [升级维护](#升级维护)
- [应急响应](#应急响应)

## 日常运维

### 服务启动

```bash
# 使用 Docker Compose 启动
./scripts/deploy.sh start

# 检查服务状态
./scripts/deploy.sh status

# 查看启动日志
./scripts/deploy.sh logs cdc-service
```

### 服务停止

```bash
# 优雅停止服务
./scripts/deploy.sh stop

# 强制停止（不推荐）
docker stop -t 0 mysql-cdc-service
```

### 服务重启

```bash
# 重启服务
./scripts/deploy.sh restart

# 重启后检查健康状态
curl http://localhost:8080/health
```

### 日志查看

```bash
# 查看实时日志
./scripts/deploy.sh logs cdc-service true

# 查看最近 100 行日志
docker logs --tail 100 mysql-cdc-service

# 查看错误日志
docker exec mysql-cdc-service tail -f /app/logs/cdc-service-error.log

# 查看 GC 日志
docker exec mysql-cdc-service tail -f /app/logs/gc.log
```

### 配置更新

```bash
# 1. 修改配置文件
vim docker/application.conf

# 2. 重启服务使配置生效
./scripts/deploy.sh restart

# 3. 验证配置
curl http://localhost:8080/status
```

## 部署操作

### 首次部署

```bash
# 1. 克隆代码
git clone <repository-url>
cd mysql-cdc-service

# 2. 配置环境变量
cp .env.example .env
vim .env

# 3. 构建镜像
./scripts/build.sh docker

# 4. 启动服务
./scripts/deploy.sh start

# 5. 验证部署
curl http://localhost:8080/health
```

### 生产环境部署

```bash
# 1. 准备生产配置
cp docker/application.conf config/production.conf
vim config/production.conf

# 2. 构建生产镜像
./scripts/build.sh docker 1.0.0

# 3. 推送到镜像仓库
./scripts/build.sh push 1.0.0 registry.example.com

# 4. 在生产服务器部署
docker pull registry.example.com/mysql-cdc-service:1.0.0
docker-compose -f docker-compose.prod.yml up -d

# 5. 验证部署
curl http://production-host:8080/health
```

### 多实例部署

```bash
# 部署多个实例处理不同的表
docker-compose -f docker-compose.yml \
  -f docker-compose.instance1.yml up -d

docker-compose -f docker-compose.yml \
  -f docker-compose.instance2.yml up -d
```

## 监控检查

### 健康检查

```bash
# 基础健康检查
curl http://localhost:8080/health

# 详细状态检查
curl http://localhost:8080/status | jq .

# 检查所有组件
curl http://localhost:8080/api/health/detailed | jq .
```

### 指标查询

```bash
# 查看所有指标
curl http://localhost:9090/metrics

# 查看 Binlog Lag
curl http://localhost:9090/metrics | grep cdc_binlog_lag_seconds

# 查看 TPS
curl http://localhost:9090/metrics | grep cdc_.*_rate

# 查看错误率
curl http://localhost:9090/metrics | grep cdc_errors_total
```

### 性能监控

```bash
# CPU 使用率
docker stats mysql-cdc-service --no-stream

# 内存使用
docker exec mysql-cdc-service free -h

# JVM 内存
docker exec mysql-cdc-service jstat -gc 1 1000 10

# 线程数
docker exec mysql-cdc-service jstack 1 | grep "Thread" | wc -l
```

### 数据库监控

```sql
-- 检查 Binlog 状态
SHOW MASTER STATUS;

-- 检查 Binlog 文件
SHOW BINARY LOGS;

-- 检查连接数
SHOW PROCESSLIST;

-- 检查表大小
SELECT 
  table_name,
  ROUND(((data_length + index_length) / 1024 / 1024), 2) AS size_mb
FROM information_schema.tables
WHERE table_schema = 'source_db'
ORDER BY size_mb DESC
LIMIT 10;
```

## 备份恢复

### 数据备份

```bash
# 备份所有数据
./scripts/deploy.sh backup

# 备份到指定目录
BACKUP_DIR=/backup/$(date +%Y%m%d)
mkdir -p $BACKUP_DIR

# 备份源数据库
docker exec source-mysql mysqldump \
  -u root -ppassword \
  --all-databases \
  --single-transaction \
  --routines \
  --triggers \
  > $BACKUP_DIR/source_db.sql

# 备份目标数据库
docker exec target-mysql mysqldump \
  -u root -ppassword \
  --all-databases \
  --single-transaction \
  --routines \
  --triggers \
  > $BACKUP_DIR/target_db.sql

# 备份偏移量
docker exec target-mysql mysqldump \
  -u root -ppassword \
  source_db cdc_offsets \
  > $BACKUP_DIR/offsets.sql

# 备份配置
cp -r docker $BACKUP_DIR/
cp docker-compose.yml $BACKUP_DIR/
```

### 数据恢复

```bash
# 恢复数据
./scripts/deploy.sh restore /backup/20240101

# 手动恢复
docker exec -i source-mysql mysql -u root -ppassword < backup/source_db.sql
docker exec -i target-mysql mysql -u root -ppassword < backup/target_db.sql

# 恢复偏移量
docker exec -i target-mysql mysql -u root -ppassword source_db < backup/offsets.sql
```

### 灾难恢复

```bash
# 1. 停止服务
./scripts/deploy.sh stop

# 2. 恢复数据库
./scripts/deploy.sh restore /backup/latest

# 3. 验证数据
mysql -h target-host -u root -p -e "SELECT COUNT(*) FROM users;"

# 4. 重启服务
./scripts/deploy.sh start

# 5. 监控同步状态
watch -n 5 'curl -s http://localhost:8080/status | jq .'
```

## 升级维护

### 版本升级

```bash
# 1. 备份当前数据
./scripts/deploy.sh backup

# 2. 停止服务
./scripts/deploy.sh stop

# 3. 拉取新版本
docker pull registry.example.com/mysql-cdc-service:1.1.0

# 4. 更新 docker-compose.yml
vim docker-compose.yml
# 修改镜像版本为 1.1.0

# 5. 启动新版本
./scripts/deploy.sh start

# 6. 验证升级
curl http://localhost:8080/health
curl http://localhost:8080/status | jq .version
```

### 配置升级

```bash
# 1. 备份当前配置
cp docker/application.conf docker/application.conf.bak

# 2. 更新配置
vim docker/application.conf

# 3. 验证配置语法
# 可以使用配置验证工具

# 4. 重启服务
./scripts/deploy.sh restart

# 5. 检查日志确认配置生效
./scripts/deploy.sh logs cdc-service | grep "Configuration loaded"
```

### 数据库升级

```bash
# MySQL 5.7 → 8.0 升级

# 1. 备份数据
./scripts/deploy.sh backup

# 2. 停止 CDC 服务
./scripts/deploy.sh stop

# 3. 升级 MySQL
docker-compose down
# 修改 docker-compose.yml 中的 MySQL 版本
docker-compose up -d source-mysql target-mysql

# 4. 验证 MySQL 升级
docker exec source-mysql mysql --version

# 5. 重启 CDC 服务
./scripts/deploy.sh start
```

### 滚动升级（零停机）

```bash
# 1. 部署新版本实例
docker-compose -f docker-compose.new.yml up -d

# 2. 等待新实例就绪
while ! curl -f http://localhost:8081/health; do
  sleep 5
done

# 3. 切换流量（通过负载均衡器）
# 更新负载均衡器配置，将流量切换到新实例

# 4. 停止旧实例
docker-compose -f docker-compose.old.yml down

# 5. 验证
curl http://localhost:8080/status
```

## 应急响应

### 服务异常

```bash
# 1. 检查服务状态
docker ps -a | grep cdc

# 2. 查看错误日志
docker logs --tail 100 mysql-cdc-service

# 3. 检查资源使用
docker stats mysql-cdc-service --no-stream

# 4. 重启服务
./scripts/deploy.sh restart

# 5. 如果重启失败，查看详细日志
docker logs mysql-cdc-service > /tmp/cdc-error.log
```

### 数据同步延迟

```bash
# 1. 检查 Binlog Lag
curl http://localhost:9090/metrics | grep cdc_binlog_lag_seconds

# 2. 检查队列深度
curl http://localhost:9090/metrics | grep cdc_queue_depth

# 3. 检查 TPS
curl http://localhost:8080/status | jq '.cdc.ingestTPS, .cdc.applyTPS'

# 4. 临时增加并行度
# 修改配置文件
vim docker/application.conf
# 增加 apply-workers 和 router-partitions

# 5. 重启服务
./scripts/deploy.sh restart
```

### 数据库连接失败

```bash
# 1. 检查数据库状态
docker ps | grep mysql

# 2. 测试连接
mysql -h source-host -P 3306 -u root -p

# 3. 检查网络
ping source-host
telnet source-host 3306

# 4. 检查防火墙
sudo iptables -L | grep 3306

# 5. 重启数据库（如果需要）
docker restart source-mysql
```

### 内存溢出

```bash
# 1. 检查内存使用
docker stats mysql-cdc-service --no-stream

# 2. 生成堆转储
docker exec mysql-cdc-service jmap -dump:format=b,file=/tmp/heap.bin 1

# 3. 分析堆转储
# 下载并使用 MAT 或 VisualVM 分析

# 4. 临时增加内存
docker-compose down
# 修改 docker-compose.yml 中的 JAVA_OPTS
# JAVA_OPTS: "-Xms2g -Xmx4g"
docker-compose up -d

# 5. 优化配置
# 减少热表集大小
# 减少批处理大小
```

### DDL 事件阻塞

```bash
# 1. 查看 DDL 告警
curl http://localhost:8080/api/ddl/alerts | jq .

# 2. 查看 DDL 历史
curl http://localhost:8080/api/ddl/history | jq .

# 3. 手动处理 DDL
# 在目标数据库执行相同的 DDL
mysql -h target-host -u root -p target_db
> ALTER TABLE users ADD COLUMN new_column VARCHAR(100);

# 4. 清除告警
curl -X POST http://localhost:8080/api/ddl/alerts/clear

# 5. 恢复服务
./scripts/deploy.sh restart
```

### 数据不一致

```bash
# 1. 停止服务
./scripts/deploy.sh stop

# 2. 对比数据
# 源数据库
mysql -h source-host -u root -p -e "SELECT COUNT(*) FROM source_db.users;"

# 目标数据库
mysql -h target-host -u root -p -e "SELECT COUNT(*) FROM target_db.users;"

# 3. 找出差异
mysql -h source-host -u root -p source_db -e "
  SELECT id FROM users 
  WHERE id NOT IN (
    SELECT id FROM target_db.users
  );" > /tmp/missing_ids.txt

# 4. 手动修复或重新同步
# 选项 A: 手动修复
# 选项 B: 清除偏移量，重新快照

# 5. 重启服务
./scripts/deploy.sh start
```

## 定期维护任务

### 每日任务

```bash
#!/bin/bash
# daily_maintenance.sh

# 1. 检查服务健康状态
curl -f http://localhost:8080/health || alert "CDC service unhealthy"

# 2. 检查 Binlog Lag
LAG=$(curl -s http://localhost:9090/metrics | grep cdc_binlog_lag_seconds | awk '{print $2}')
if (( $(echo "$LAG > 60" | bc -l) )); then
  alert "Binlog lag too high: $LAG seconds"
fi

# 3. 检查错误率
ERROR_RATE=$(curl -s http://localhost:8080/status | jq -r '.cdc.errorRate')
if (( $(echo "$ERROR_RATE > 0.01" | bc -l) )); then
  alert "Error rate too high: $ERROR_RATE"
fi

# 4. 清理旧日志
find /app/logs -name "*.log.*" -mtime +7 -delete
```

### 每周任务

```bash
#!/bin/bash
# weekly_maintenance.sh

# 1. 完整备份
./scripts/deploy.sh backup

# 2. 检查磁盘空间
df -h | grep -E '(8[0-9]|9[0-9]|100)%' && alert "Disk space low"

# 3. 分析慢查询
docker exec target-mysql mysqldumpslow /var/log/mysql/slow.log

# 4. 优化表
mysql -h target-host -u root -p -e "
  SELECT CONCAT('OPTIMIZE TABLE ', table_schema, '.', table_name, ';')
  FROM information_schema.tables
  WHERE table_schema = 'target_db'
  AND data_free > 100 * 1024 * 1024;
" | mysql -h target-host -u root -p

# 5. 更新统计信息
mysql -h target-host -u root -p -e "ANALYZE TABLE target_db.users;"
```

### 每月任务

```bash
#!/bin/bash
# monthly_maintenance.sh

# 1. 性能测试
# 运行性能测试套件

# 2. 容量规划
# 分析增长趋势，预测资源需求

# 3. 安全审计
# 检查用户权限、密码策略等

# 4. 文档更新
# 更新运维文档和配置文档

# 5. 灾难恢复演练
# 测试备份恢复流程
```

## 监控告警配置

### Prometheus 告警规则

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
      
      - alert: HighErrorRate
        expr: rate(cdc_errors_total[5m]) > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Error rate is too high"
          description: "Error rate is {{ $value }}"
      
      - alert: ServiceDown
        expr: up{job="mysql-cdc-service"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "CDC service is down"
```

### 告警通知配置

```yaml
# alertmanager.yml
route:
  receiver: 'team-email'
  group_by: ['alertname', 'severity']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h

receivers:
  - name: 'team-email'
    email_configs:
      - to: 'ops-team@example.com'
        from: 'alertmanager@example.com'
        smarthost: 'smtp.example.com:587'
        auth_username: 'alertmanager@example.com'
        auth_password: 'password'
```

## 故障预案

### 预案 1: 服务完全不可用

1. 立即通知相关人员
2. 检查基础设施（服务器、网络、数据库）
3. 查看错误日志定位问题
4. 尝试重启服务
5. 如果重启失败，回滚到上一个稳定版本
6. 记录故障原因和处理过程

### 预案 2: 数据同步严重延迟

1. 检查 Binlog Lag 和队列深度
2. 检查目标数据库性能
3. 临时增加并行度和批处理大小
4. 如果延迟持续增长，考虑暂停非关键表的同步
5. 优化目标数据库（增加索引、调整配置）
6. 监控延迟恢复情况

### 预案 3: 数据不一致

1. 立即停止服务，防止不一致扩大
2. 对比源和目标数据库，找出差异
3. 分析不一致原因（代码bug、配置错误、网络问题）
4. 修复根本原因
5. 手动修复数据或重新同步
6. 验证数据一致性后恢复服务

## 联系方式

- **运维团队**: ops-team@example.com
- **开发团队**: dev-team@example.com
- **紧急联系**: +86-xxx-xxxx-xxxx
- **文档地址**: https://docs.example.com