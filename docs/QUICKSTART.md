# 快速入门指南

5 分钟快速体验 MySQL CDC Service。

## 前置条件

- Docker 和 Docker Compose 已安装
- 至少 4GB 可用内存
- 端口 3306, 3307, 8080, 9090, 9091, 3000 未被占用

## 步骤 1: 启动服务

```bash
# 克隆项目
git clone <repository-url>
cd mysql-cdc-service

# 启动所有服务（包括 MySQL、CDC、Prometheus、Grafana）
./scripts/deploy.sh start
```

等待约 1-2 分钟，直到所有服务启动完成。

## 步骤 2: 验证服务

```bash
# 检查服务状态
./scripts/deploy.sh status

# 检查健康状态
curl http://localhost:8080/health
```

预期输出：
```json
{
  "status": "healthy",
  "timestamp": "2024-01-01T12:00:00Z",
  "uptime": "PT2M30S",
  "version": "1.0.0"
}
```

## 步骤 3: 测试数据同步

### 3.1 连接源数据库

```bash
# 连接到源 MySQL
docker exec -it cdc-source-mysql mysql -u root -ppassword source_db
```

### 3.2 插入测试数据

```sql
-- 插入新用户
INSERT INTO users (username, email) 
VALUES ('test_user', 'test@example.com');

-- 查询确认
SELECT * FROM users WHERE username = 'test_user';
```

### 3.3 验证目标数据库

```bash
# 打开新终端，连接到目标 MySQL
docker exec -it cdc-target-mysql mysql -u root -ppassword target_db
```

```sql
-- 查询同步的数据（应该在几秒内出现）
SELECT * FROM users WHERE username = 'test_user';
```

### 3.4 测试更新操作

```sql
-- 在源数据库更新
UPDATE users SET email = 'updated@example.com' WHERE username = 'test_user';

-- 在目标数据库验证（切换到目标数据库终端）
SELECT * FROM users WHERE username = 'test_user';
-- 应该看到 email 已更新
```

### 3.5 测试删除操作

```sql
-- 在源数据库删除
DELETE FROM users WHERE username = 'test_user';

-- 在目标数据库验证
SELECT * FROM users WHERE username = 'test_user';
-- 应该返回空结果
```

## 步骤 4: 查看监控

### 4.1 查看实时指标

```bash
# 查看 CDC 指标
curl http://localhost:9090/metrics | grep cdc_

# 查看状态
curl http://localhost:8080/status | jq .
```

### 4.2 访问 Prometheus

打开浏览器访问: http://localhost:9091

查询示例：
- `cdc_events_ingested_total`: 接收事件总数
- `cdc_binlog_lag_seconds`: Binlog 延迟
- `rate(cdc_events_applied_total[1m])`: 每分钟应用事件数

### 4.3 访问 Grafana

打开浏览器访问: http://localhost:3000

- 用户名: `admin`
- 密码: `admin`

导入预置的 CDC 仪表板查看可视化监控。

## 步骤 5: 测试高级特性

### 5.1 测试批量插入

```sql
-- 在源数据库执行
INSERT INTO users (username, email) VALUES
  ('user1', 'user1@example.com'),
  ('user2', 'user2@example.com'),
  ('user3', 'user3@example.com'),
  ('user4', 'user4@example.com'),
  ('user5', 'user5@example.com');

-- 查看同步速度
-- 在目标数据库验证
SELECT COUNT(*) FROM users WHERE username LIKE 'user%';
```

### 5.2 查看热表集状态

```bash
# 查看热表集大小
curl http://localhost:9090/metrics | grep cdc_hot_set_size

# 查看表温度
curl http://localhost:9090/metrics | grep cdc_table_temperature
```

### 5.3 测试 DDL 处理

```sql
-- 在源数据库执行 DDL
ALTER TABLE users ADD COLUMN phone VARCHAR(20);

-- 查看 DDL 告警
curl http://localhost:8080/api/ddl/alerts | jq .

-- 手动在目标数据库执行相同的 DDL
ALTER TABLE users ADD COLUMN phone VARCHAR(20);
```

## 步骤 6: 性能测试

### 6.1 生成测试数据

```bash
# 使用脚本生成大量测试数据
docker exec -i cdc-source-mysql mysql -u root -ppassword source_db << 'EOF'
DELIMITER $$
CREATE PROCEDURE generate_test_data(IN num_rows INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  WHILE i < num_rows DO
    INSERT INTO users (username, email) 
    VALUES (CONCAT('user_', i), CONCAT('user_', i, '@example.com'));
    SET i = i + 1;
  END WHILE;
END$$
DELIMITER ;

-- 生成 10000 条测试数据
CALL generate_test_data(10000);
EOF
```

### 6.2 监控同步性能

```bash
# 实时查看 TPS
watch -n 1 'curl -s http://localhost:8080/status | jq ".cdc.ingestTPS, .cdc.applyTPS"'

# 查看延迟
watch -n 1 'curl -s http://localhost:9090/metrics | grep cdc_binlog_lag_seconds'
```

## 步骤 7: 清理环境

```bash
# 停止所有服务
./scripts/deploy.sh stop

# 清理数据（可选）
./scripts/deploy.sh cleanup
```

## 常见问题

### Q1: 服务启动失败

**A**: 检查端口是否被占用
```bash
# 检查端口占用
lsof -i :8080
lsof -i :3306

# 修改端口配置
vim docker-compose.yml
```

### Q2: 数据没有同步

**A**: 检查表过滤配置
```bash
# 查看配置
cat docker/application.conf | grep -A 5 "binlog"

# 确保表在 include-tables 中
```

### Q3: 同步延迟高

**A**: 调整性能参数
```bash
# 编辑配置文件
vim docker/application.conf

# 增加并行度
cdc {
  parallelism {
    apply-workers = 8
    router-partitions = 32
  }
}

# 重启服务
./scripts/deploy.sh restart
```

## 下一步

- 阅读 [配置文档](CONFIGURATION.md) 了解详细配置
- 阅读 [架构文档](ARCHITECTURE.md) 了解系统设计
- 阅读 [故障排查手册](TROUBLESHOOTING.md) 学习问题诊断
- 阅读 [运维手册](OPERATIONS.md) 了解日常运维

## 生产环境部署

在生产环境部署前，请：

1. 仔细阅读所有文档
2. 在测试环境充分测试
3. 制定详细的部署计划
4. 准备回滚方案
5. 配置监控告警
6. 进行灾难恢复演练

---

**祝您使用愉快！如有问题，请查阅文档或联系支持团队。**