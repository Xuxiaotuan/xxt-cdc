package cn.xuyinyin.cdc.connector

import cn.xuyinyin.cdc.connector.source.mysql.{MySQLSourceConnector => MySQLSource}
import cn.xuyinyin.cdc.connector.sink.mysql.{MySQLSinkConnector => MySQLSink}
import cn.xuyinyin.cdc.connector.sink.starrocks.{StarRocksSinkConnector => StarRocksSink}
import com.typesafe.scalalogging.LazyLogging

/**
 * Connector 启动器
 * 
 * 负责在应用启动时注册所有可用的 Connector
 * 
 * 使用方式：
 * 1. 在应用启动时调用 ConnectorBootstrap.initialize()
 * 2. 系统会自动注册所有内置的 Connector
 * 3. 可以通过 ConnectorRegistry 查询和使用 Connector
 */
object ConnectorBootstrap extends LazyLogging {
  
  @volatile private var initialized = false
  
  /**
   * 初始化所有 Connector
   * 
   * 此方法是幂等的，多次调用只会执行一次
   */
  def initialize(): Unit = {
    if (initialized) {
      logger.debug("Connectors already initialized, skipping")
      return
    }
    
    synchronized {
      if (initialized) return
      
      logger.info("Initializing CDC Connectors...")
      
      // 注册 MySQL Connector
      registerMySQLConnectors()
      
      // 注册 StarRocks Connector
      registerStarRocksConnectors()
      
      // 未来可以在这里注册更多 Connector：
      // registerPostgreSQLConnectors()
      // registerClickHouseConnectors()
      // registerKafkaConnectors()
      
      initialized = true
      
      // 输出注册摘要
      logger.info(s"Connector initialization complete:")
      logger.info(s"  - Source connectors: ${ConnectorRegistry.listSources().mkString(", ")}")
      logger.info(s"  - Sink connectors: ${ConnectorRegistry.listSinks().mkString(", ")}")
    }
  }
  
  /**
   * 注册 MySQL Connector
   */
  private def registerMySQLConnectors(): Unit = {
    try {
      ConnectorRegistry.registerSource(MySQLSource())
      ConnectorRegistry.registerSink(MySQLSink())
      logger.info("MySQL connectors registered successfully")
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to register MySQL connectors: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  /**
   * 注册 StarRocks Connector
   */
  private def registerStarRocksConnectors(): Unit = {
    try {
      ConnectorRegistry.registerSink(StarRocksSink())
      logger.info("StarRocks connectors registered successfully")
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to register StarRocks connectors: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  /**
   * 检查是否已初始化
   */
  def isInitialized: Boolean = initialized
  
  /**
   * 重置初始化状态（主要用于测试）
   */
  def reset(): Unit = {
    synchronized {
      ConnectorRegistry.clear()
      initialized = false
      logger.info("Connector bootstrap reset")
    }
  }
}
