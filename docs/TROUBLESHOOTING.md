# 故障排查手册

本文档提供 MySQL CDC Service 常见问题的诊断和解决方案。

## 目录

- [服务启动问题](#服务启动问题)
- [数据同步问题](#数据同步问题)
- [性能问题](#性能问题)
- [数据一致性问题](#数据一致性问题)
- [监控和告警](#监控和告警)

## 服务启动问题

### 问题 1: 服务无法启动

**症状**：
```
Error: Failed to connect to MySQL server
```

**可能原因**：
1. MySQL 服务未启动
2. 网络连接问题
3. 认证信息错误
4. 防火墙阻止连接

**诊断步骤**：

1. **检查 MySQL 服务状态**
```bash
# 检查 MySQL 是否运行
docker ps | grep mysql
# 或
systemctl status mysql
```

2. **测试网络连通性**
```bash
# 测试端口是否可达
telnet <mysql-host> 3306
# 或
nc -zv <mysql-host> 3306
```

3. **验证认证信息**
```bash
# 尝试手动连接
mysql -h <host> -P <port> -u <username> -p
```

4. **检查日志**
```bash
# 查看 CDC 服务日志
./scripts/deploy.sh logs cdc-service

# 查看 MySQL 错误日志
docker exec source-mysql tail -f /var/log/mysql/error.log
```

**解决方案**：
- 确保 MySQL 服务正常运行
- 检查并修正配置文件中的连接信息
- 确认防火墙规则允许连接
- 验证用户权限是否正确

### 问题 2: Binlog 未启用

**症状**：
```
Error: The MySQL server is not configured as a replication master
```

**诊断步骤**：

1. **检查 Binlog 配置**
```sql
-- 连接到 MySQL
mysql -u root -p

-- 检查 Binlog 是否启用
SHOW VARIABLES LIKE 'log_bin';

-- 检查 Binlog 格式
SHOW VARIABLES LIKE 'binlog_format';

-- 检查 Server ID
SHOW VARIABLES LIKE 'server_id';
```

2. **查看 Binlog 文件**
```sql
SHOW BINARY LOGS;
```

**解决方案**：

1. **启用 Binlog**（需要重启 MySQL）

编辑 MySQL 配置文件（`/etc/mysql/my.cnf` 或 `/etc/my.cnf`）：
```ini
[mysqld]
server-id = 1
log-bin = mysql-bin
binlog-format = ROW
```

2. **重启 MySQL**
```bash
systemctl restart mysql
# 或
docker restart source-mysql
```

3. **验证配置**
```sql
SHOW VARIABLES LIKE 'log_bin';
-- 应该显示 ON
```

### 问题 3: 权限不足

**症状**：
```
Error: Access denied for user 'cdc_user'@'%' to database 'source_db'
```

**解决方案**：

```sql
-- 创建 CDC 用户
CREATE USER 'cdc_user'@'%' IDENTIFIED BY 'password';

-- 授予必要权限
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT 
ON *.* TO 'cdc_user'@'%';

-- 授予数据库权限
GRANT ALL PRIVILEGES ON source_db.* TO 'cdc_user'@'%';
GRANT ALL PRIVILEGES ON target_db.* TO 'cdc_user'@'%';

-- 刷新权限
FLUSH PRIVILEGES;
```

## 数据同步问题

### 问题 4: 数据同步延迟高

**症状**：
- `cdc_binlog_lag_seconds` 指标持续增长
- 目标数据库数据更新不及时

**诊断步骤**：

1. **检查 Binlog Lag**
```bash
curl http://localhost:8080/metrics | grep cdc_binlog_lag_seconds
```

2. **检查处理速率**
```bash
curl http://localhost:8080/status
```

3. **检查队列深度**
```bash
curl http://localhost:8080/metrics | grep cdc_queue_depth
```

4. **检查目标数据库性能**
```sql
-- 检查慢查询
SHOW PROCESSLIST;

-- 检查锁等待
SHOW ENGINE INNODB STATUS;
```

**解决方案**：

1. **增加并行度**
```hocon
cdc {
  parallelism {
    apply-workers = 8      # 增加工作线程
    router-partitions = 32 # 增加分区数
  }
}
```

2. **优化批处理**
```hocon
cdc {
  batch {
    size = 2000           # 增加批处理大小
    flush-interval = "3s" # 减少刷新间隔
  }
}
```

3. **优化目标数据库**
```sql
-- 增加 InnoDB 缓冲池
SET GLOBAL innodb_buffer_pool_size = 2147483648; -- 2GB

-- 调整刷新策略
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
```

4. **增加连接池**
```hocon
target {
  mysql {
    connection-pool {
      maximum-pool-size = 50
      minimum-idle = 10
    }
  }
}
```

### 问题 5: 数据未同步

**症状**：
- 源数据库有变更，但目标数据库没有更新
- 没有错误日志

**诊断步骤**：

1. **检查表过滤规则**
```bash
# 查看配置
cat docker/application.conf | grep -A 5 "binlog"
```

2. **检查事件接收**
```bash
curl http://localhost:8080/metrics | grep cdc_events_ingested_total
```

3. **检查事件应用**
```bash
curl http://localhost:8080/metrics | grep cdc_events_applied_total
```

4. **检查错误计数**
```bash
curl http://localhost:8080/metrics | grep cdc_errors_total
```

**解决方案**：

1. **检查表过滤配置**
```hocon
source {
  mysql {
    binlog {
      # 确保表在包含列表中
      include-tables = ["users", "orders"]
      # 确保表不在排除列表中
      exclude-tables = ["logs.*"]
    }
  }
}
```

2. **检查目标表是否存在**
```sql
-- 在目标数据库中
SHOW TABLES LIKE 'users';
```

3. **查看详细日志**
```bash
docker exec mysql-cdc-service tail -f /app/logs/cdc-service.log
```

### 问题 6: DDL 事件导致同步停止

**症状**：
```
Error: DDL event detected: ALTER_TABLE on table users
```

**解决方案**：

1. **调整 DDL 处理策略**
```hocon
cdc {
  ddl {
    strategy = "log"  # 改为 log 或 ignore
  }
}
```

2. **手动处理 DDL**
```sql
-- 在目标数据库执行相同的 DDL
ALTER TABLE users ADD COLUMN new_column VARCHAR(100);
```

3. **重启服务**
```bash
./scripts/deploy.sh restart
```

## 性能问题

### 问题 7: CPU 使用率高

**诊断步骤**：

1. **检查线程数**
```bash
# 查看 Java 线程
docker exec mysql-cdc-service jstack 1 | grep "Thread" | wc -l
```

2. **检查 GC 情况**
```bash
# 查看 GC 日志
docker exec mysql-cdc-service tail -f /app/logs/gc.log
```

3. **检查处理速率**
```bash
curl http://localhost:8080/metrics | grep cdc_ingest_rate
```

**解决方案**：

1. **调整并行度**
```hocon
cdc {
  parallelism {
    apply-workers = 4  # 减少到 CPU 核心数
  }
}
```

2. **优化 GC**
```bash
# 调整 JVM 参数
JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

3. **减少批处理频率**
```hocon
cdc {
  batch {
    size = 2000
    flush-interval = "5s"  # 增加刷新间隔
  }
}
```

### 问题 8: 内存使用过高

**诊断步骤**：

1. **检查内存使用**
```bash
# 查看容器内存
docker stats mysql-cdc-service

# 查看 JVM 内存
docker exec mysql-cdc-service jstat -gc 1
```

2. **检查热表集大小**
```bash
curl http://localhost:8080/metrics | grep cdc_hot_set_size
```

3. **检查队列深度**
```bash
curl http://localhost:8080/metrics | grep cdc_queue_depth
```

**解决方案**：

1. **增加堆内存**
```bash
JAVA_OPTS="-Xms2g -Xmx4g"
```

2. **限制热表集大小**
```hocon
cdc {
  hot-set {
    max-tables = 500  # 减少热表集大小
  }
}
```

3. **优化批处理**
```hocon
cdc {
  batch {
    size = 1000  # 减少批处理大小
  }
}
```

4. **检查内存泄漏**
```bash
# 生成堆转储
docker exec mysql-cdc-service jmap -dump:format=b,file=/tmp/heap.bin 1

# 使用 MAT 或 VisualVM 分析
```

### 问题 9: 网络带宽瓶颈

**症状**：
- 网络 I/O 使用率接近 100%
- 数据同步延迟高

**诊断步骤**：

1. **检查网络流量**
```bash
# 查看网络统计
docker exec mysql-cdc-service netstat -i

# 使用 iftop 监控
iftop -i eth0
```

2. **检查数据传输量**
```bash
curl http://localhost:8080/metrics | grep cdc_events
```

**解决方案**：

1. **启用数据压缩**（如果 MySQL 支持）
```hocon
source {
  mysql {
    # 在 JDBC URL 中添加压缩参数
    url = "jdbc:mysql://host:port/db?useCompression=true"
  }
}
```

2. **优化批处理**
```hocon
cdc {
  batch {
    size = 5000  # 增加批处理大小，减少网络往返
  }
}
```

3. **考虑网络升级**
- 使用更快的网络连接
- 将服务部署在同一数据中心

## 数据一致性问题

### 问题 10: 数据不一致

**症状**：
- 源数据库和目标数据库数据不一致
- 某些记录缺失或重复

**诊断步骤**：

1. **检查偏移量状态**
```bash
# 查看偏移量表
mysql -h target-host -u root -p -e "SELECT * FROM cdc_offsets;"
```

2. **检查错误日志**
```bash
docker exec mysql-cdc-service tail -f /app/logs/cdc-service-error.log
```

3. **对比数据**
```sql
-- 在源数据库
SELECT COUNT(*) FROM users;

-- 在目标数据库
SELECT COUNT(*) FROM users;
```

**解决方案**：

1. **重新同步表**
```bash
# 停止服务
./scripts/deploy.sh stop

# 清理偏移量
mysql -h target-host -u root -p -e "DELETE FROM cdc_offsets WHERE id = 'table_name';"

# 重启服务（会触发快照）
./scripts/deploy.sh start
```

2. **检查幂等性配置**
```hocon
# 确保启用幂等写入
cdc {
  sink {
    idempotent = true
  }
}
```

3. **手动修复数据**
```sql
-- 找出差异
SELECT * FROM source_db.users 
WHERE id NOT IN (SELECT id FROM target_db.users);

-- 手动插入缺失数据
INSERT INTO target_db.users SELECT * FROM source_db.users WHERE id = ?;
```

### 问题 11: 主键冲突

**症状**：
```
Error: Duplicate entry '123' for key 'PRIMARY'
```

**诊断步骤**：

1. **检查幂等性配置**
```bash
cat docker/application.conf | grep idempotent
```

2. **检查目标表结构**
```sql
SHOW CREATE TABLE users;
```

**解决方案**：

1. **确保使用幂等写入**
- 服务默认使用 `ON DUPLICATE KEY UPDATE`
- 检查配置是否正确

2. **检查主键定义**
```sql
-- 确保目标表有主键
ALTER TABLE users ADD PRIMARY KEY (id);
```

3. **清理重复数据**
```sql
-- 找出重复数据
SELECT id, COUNT(*) FROM users GROUP BY id HAVING COUNT(*) > 1;

-- 删除重复数据
DELETE t1 FROM users t1
INNER JOIN users t2 
WHERE t1.id = t2.id AND t1.created_at < t2.created_at;
```

## 监控和告警

### 问题 12: 指标不更新

**症状**：
- Prometheus 指标不更新
- Grafana 仪表板显示 "No Data"

**诊断步骤**：

1. **检查 Prometheus 端点**
```bash
curl http://localhost:9090/metrics
```

2. **检查 Prometheus 配置**
```bash
cat docker/prometheus/prometheus.yml
```

3. **检查 Prometheus 日志**
```bash
docker logs cdc-prometheus
```

**解决方案**：

1. **重启 Prometheus**
```bash
docker restart cdc-prometheus
```

2. **检查网络连接**
```bash
# 从 Prometheus 容器测试连接
docker exec cdc-prometheus wget -O- http://cdc-service:9090/metrics
```

3. **重新加载配置**
```bash
curl -X POST http://localhost:9091/-/reload
```

### 问题 13: 告警未触发

**症状**：
- 系统出现问题但没有收到告警

**解决方案**：

1. **检查 DDL 告警配置**
```hocon
cdc {
  ddl {
    enable-alerts = true
  }
}
```

2. **配置 Alertmanager**（如果使用）
```yaml
# alertmanager.yml
route:
  receiver: 'email'
  
receivers:
  - name: 'email'
    email_configs:
      - to: 'admin@example.com'
        from: 'alertmanager@example.com'
```

3. **查看告警历史**
```bash
curl http://localhost:8080/api/ddl/alerts
```

## 常用诊断命令

### 健康检查
```bash
# 服务健康状态
curl http://localhost:8080/health

# 详细状态
curl http://localhost:8080/status

# 指标查询
curl http://localhost:9090/metrics
```

### 日志查看
```bash
# 实时日志
./scripts/deploy.sh logs cdc-service true

# 错误日志
docker exec mysql-cdc-service tail -f /app/logs/cdc-service-error.log

# GC 日志
docker exec mysql-cdc-service tail -f /app/logs/gc.log
```

### 数据库诊断
```sql
-- 检查 Binlog 位置
SHOW MASTER STATUS;

-- 检查复制状态
SHOW SLAVE STATUS\G

-- 检查表大小
SELECT 
  table_name,
  ROUND(((data_length + index_length) / 1024 / 1024), 2) AS size_mb
FROM information_schema.tables
WHERE table_schema = 'source_db'
ORDER BY size_mb DESC;
```

### 性能分析
```bash
# CPU 使用
docker stats mysql-cdc-service

# 线程转储
docker exec mysql-cdc-service jstack 1 > thread_dump.txt

# 堆转储
docker exec mysql-cdc-service jmap -dump:format=b,file=/tmp/heap.bin 1
```

## 获取帮助

如果以上方法无法解决问题，请：

1. **收集诊断信息**
   - 服务日志
   - 配置文件
   - 错误信息
   - 指标数据

2. **提交 Issue**
   - GitHub Issues: https://github.com/example/mysql-cdc-service/issues
   - 包含完整的错误信息和环境描述

3. **联系支持**
   - 邮件: support@example.com
   - 文档: https://docs.example.com

## 预防措施

1. **定期备份**
```bash
./scripts/deploy.sh backup
```

2. **监控关键指标**
   - Binlog Lag
   - 错误率
   - 队列深度
   - 内存使用

3. **定期检查**
   - 日志文件大小
   - 磁盘空间
   - 数据库连接数
   - 偏移量状态

4. **测试环境验证**
   - 在测试环境先验证配置变更
   - 进行压力测试
   - 验证故障恢复流程