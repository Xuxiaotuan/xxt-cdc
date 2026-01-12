package cn.xuyinyin.cdc.connector.jdbc

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.connector.ConnectorConfig
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Connection
import javax.sql.DataSource

/**
 * JDBC 连接管理器
 * 
 * 提供统一的连接池管理，支持不同的 JDBC 驱动
 */
class JdbcConnectionManager(
  dbConfig: DatabaseConfig,
  connectorConfig: ConnectorConfig,
  jdbcDriverClass: String,
  jdbcUrlTemplate: String
) extends LazyLogging {
  
  private val dataSource: HikariDataSource = initializeDataSource()
  
  /**
   * 初始化 HikariCP 连接池
   */
  private def initializeDataSource(): HikariDataSource = {
    // 加载 JDBC 驱动
    Class.forName(jdbcDriverClass)
    
    val hikariConfig = new HikariConfig()
    
    // 构建 JDBC URL
    val jdbcUrl = buildJdbcUrl()
    hikariConfig.setJdbcUrl(jdbcUrl)
    hikariConfig.setUsername(dbConfig.username)
    hikariConfig.setPassword(dbConfig.password)
    
    // 连接池配置
    hikariConfig.setMaximumPoolSize(dbConfig.connectionPool.maxPoolSize)
    hikariConfig.setMinimumIdle(dbConfig.connectionPool.minIdle)
    hikariConfig.setConnectionTimeout(dbConfig.connectionPool.connectionTimeout.toMillis)
    
    // 性能优化配置
    hikariConfig.setAutoCommit(true)
    
    // 应用 Connector 特定配置
    applyConnectorConfig(hikariConfig)
    
    val ds = new HikariDataSource(hikariConfig)
    logger.info(s"Initialized JDBC connection pool: driver=$jdbcDriverClass, max=${dbConfig.connectionPool.maxPoolSize}")
    ds
  }
  
  /**
   * 构建 JDBC URL
   */
  private def buildJdbcUrl(): String = {
    val baseUrl = jdbcUrlTemplate
      .replace("{host}", dbConfig.host)
      .replace("{port}", dbConfig.port.toString)
      .replace("{database}", dbConfig.database)
    
    // 添加 URL 参数
    val urlParams = connectorConfig.properties
      .filter { case (k, _) => k.startsWith("jdbc.") }
      .map { case (k, v) => s"${k.stripPrefix("jdbc.")}=$v" }
      .mkString("&")
    
    if (urlParams.nonEmpty) {
      if (baseUrl.contains("?")) s"$baseUrl&$urlParams"
      else s"$baseUrl?$urlParams"
    } else {
      baseUrl
    }
  }
  
  /**
   * 应用 Connector 特定配置
   */
  private def applyConnectorConfig(hikariConfig: HikariConfig): Unit = {
    // 连接测试查询
    connectorConfig.get("connectionTestQuery").foreach(hikariConfig.setConnectionTestQuery)
    
    // 数据源属性
    connectorConfig.properties
      .filter { case (k, _) => k.startsWith("datasource.") }
      .foreach { case (k, v) =>
        hikariConfig.addDataSourceProperty(k.stripPrefix("datasource."), v)
      }
  }
  
  /**
   * 获取数据库连接
   */
  def getConnection(): Connection = {
    dataSource.getConnection()
  }
  
  /**
   * 获取数据源
   */
  def getDataSource(): DataSource = dataSource
  
  /**
   * 关闭连接池
   */
  def close(): Unit = {
    if (!dataSource.isClosed) {
      dataSource.close()
      logger.info("Closed JDBC connection pool")
    }
  }
  
  /**
   * 获取连接池统计信息
   */
  def getStatistics(): Map[String, Any] = {
    Map(
      "activeConnections" -> dataSource.getHikariPoolMXBean.getActiveConnections,
      "idleConnections" -> dataSource.getHikariPoolMXBean.getIdleConnections,
      "totalConnections" -> dataSource.getHikariPoolMXBean.getTotalConnections,
      "threadsAwaitingConnection" -> dataSource.getHikariPoolMXBean.getThreadsAwaitingConnection
    )
  }
}

object JdbcConnectionManager {
  /**
   * 创建 MySQL 连接管理器
   */
  def forMySQL(dbConfig: DatabaseConfig, connectorConfig: ConnectorConfig = ConnectorConfig.empty): JdbcConnectionManager = {
    val defaultConfig = ConnectorConfig(Map(
      "jdbc.useSSL" -> "false",
      "jdbc.allowPublicKeyRetrieval" -> "true",
      "jdbc.serverTimezone" -> "UTC",
      "jdbc.rewriteBatchedStatements" -> "true",
      "connectionTestQuery" -> "SELECT 1",
      "datasource.cachePrepStmts" -> "true",
      "datasource.prepStmtCacheSize" -> "250",
      "datasource.prepStmtCacheSqlLimit" -> "2048",
      "datasource.useServerPrepStmts" -> "true"
    )).withProperties(connectorConfig.properties)
    
    new JdbcConnectionManager(
      dbConfig,
      defaultConfig,
      "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://{host}:{port}/{database}"
    )
  }
  
  /**
   * 创建 StarRocks 连接管理器（兼容 MySQL 协议）
   */
  def forStarRocks(dbConfig: DatabaseConfig, connectorConfig: ConnectorConfig = ConnectorConfig.empty): JdbcConnectionManager = {
    val defaultConfig = ConnectorConfig(Map(
      "jdbc.useSSL" -> "false",
      "jdbc.allowPublicKeyRetrieval" -> "true",
      "jdbc.serverTimezone" -> "UTC",
      "connectionTestQuery" -> "SELECT 1"
    )).withProperties(connectorConfig.properties)
    
    new JdbcConnectionManager(
      dbConfig,
      defaultConfig,
      "com.mysql.cj.jdbc.Driver",
      "jdbc:mysql://{host}:{port}/{database}"
    )
  }
  
  /**
   * 创建 PostgreSQL 连接管理器
   */
  def forPostgreSQL(dbConfig: DatabaseConfig, connectorConfig: ConnectorConfig = ConnectorConfig.empty): JdbcConnectionManager = {
    val defaultConfig = ConnectorConfig(Map(
      "jdbc.ssl" -> "false",
      "connectionTestQuery" -> "SELECT 1"
    )).withProperties(connectorConfig.properties)
    
    new JdbcConnectionManager(
      dbConfig,
      defaultConfig,
      "org.postgresql.Driver",
      "jdbc:postgresql://{host}:{port}/{database}"
    )
  }
}
