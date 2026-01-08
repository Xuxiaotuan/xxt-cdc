# MySQL CDC Service Dockerfile
FROM openjdk:11-jre-slim

# 设置工作目录
WORKDIR /app

# 安装必要的工具
RUN apt-get update && apt-get install -y \
    curl \
    netcat \
    && rm -rf /var/lib/apt/lists/*

# 创建应用用户
RUN groupadd -r cdc && useradd -r -g cdc cdc

# 复制应用程序文件
COPY target/scala-2.13/xxt-cdc-assembly-*.jar app.jar
COPY docker/application.conf /app/conf/
COPY docker/logback.xml /app/conf/
COPY docker/entrypoint.sh /app/

# 设置权限
RUN chmod +x /app/entrypoint.sh && \
    chown -R cdc:cdc /app

# 创建日志目录
RUN mkdir -p /app/logs && chown -R cdc:cdc /app/logs

# 暴露端口
EXPOSE 8080 9090

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# 切换到应用用户
USER cdc

# 设置JVM参数
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:+UseStringDeduplication"

# 启动应用
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]