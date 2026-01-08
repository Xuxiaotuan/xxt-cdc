#!/bin/bash

# MySQL CDC Service 启动脚本

set -e

# 默认配置
DEFAULT_JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:+UseStringDeduplication"
DEFAULT_CONFIG_FILE="/app/conf/application.conf"
DEFAULT_LOG_CONFIG="/app/conf/logback.xml"

# 环境变量
JAVA_OPTS=${JAVA_OPTS:-$DEFAULT_JAVA_OPTS}
CONFIG_FILE=${CONFIG_FILE:-$DEFAULT_CONFIG_FILE}
LOG_CONFIG=${LOG_CONFIG:-$DEFAULT_LOG_CONFIG}

# 等待依赖服务
wait_for_service() {
    local host=$1
    local port=$2
    local service_name=$3
    
    echo "等待 $service_name 服务启动 ($host:$port)..."
    
    while ! nc -z "$host" "$port"; do
        echo "等待 $service_name 连接..."
        sleep 2
    done
    
    echo "$service_name 服务已就绪"
}

# 等待 MySQL 服务
if [ -n "$MYSQL_HOST" ] && [ -n "$MYSQL_PORT" ]; then
    wait_for_service "$MYSQL_HOST" "$MYSQL_PORT" "MySQL"
fi

# 等待源 MySQL 服务
if [ -n "$SOURCE_MYSQL_HOST" ] && [ -n "$SOURCE_MYSQL_PORT" ]; then
    wait_for_service "$SOURCE_MYSQL_HOST" "$SOURCE_MYSQL_PORT" "Source MySQL"
fi

# 等待目标 MySQL 服务
if [ -n "$TARGET_MYSQL_HOST" ] && [ -n "$TARGET_MYSQL_PORT" ]; then
    wait_for_service "$TARGET_MYSQL_HOST" "$TARGET_MYSQL_PORT" "Target MySQL"
fi

# 创建日志目录
mkdir -p /app/logs

# 设置 JVM 参数
export JAVA_OPTS="$JAVA_OPTS -Dconfig.file=$CONFIG_FILE -Dlogback.configurationFile=$LOG_CONFIG"

# 添加 GC 日志
export JAVA_OPTS="$JAVA_OPTS -Xlog:gc*:/app/logs/gc.log:time,uptime,level,tags"

# 添加 JMX 监控（如果启用）
if [ "$ENABLE_JMX" = "true" ]; then
    JMX_PORT=${JMX_PORT:-9999}
    export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote"
    export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=$JMX_PORT"
    export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
    export JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.ssl=false"
    echo "JMX 监控已启用，端口: $JMX_PORT"
fi

# 添加调试支持（如果启用）
if [ "$ENABLE_DEBUG" = "true" ]; then
    DEBUG_PORT=${DEBUG_PORT:-5005}
    export JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$DEBUG_PORT"
    echo "调试模式已启用，端口: $DEBUG_PORT"
fi

# 打印启动信息
echo "========================================="
echo "MySQL CDC Service 启动中..."
echo "配置文件: $CONFIG_FILE"
echo "日志配置: $LOG_CONFIG"
echo "JVM 参数: $JAVA_OPTS"
echo "========================================="

# 启动应用程序
exec "$@"