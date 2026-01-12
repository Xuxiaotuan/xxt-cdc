package cn.xuyinyin.cdc.connector

import com.typesafe.scalalogging.LazyLogging

import scala.collection.concurrent.TrieMap

/**
 * Connector 注册中心
 * 
 * 管理所有可用的 Source 和 Sink Connector，支持：
 * - 动态注册和发现 Connector
 * - 按名称查找 Connector
 * - 列出所有可用 Connector
 * 
 * 使用单例模式，全局唯一
 */
object ConnectorRegistry extends LazyLogging {
  
  private val sourceConnectors = TrieMap[String, SourceConnector]()
  private val sinkConnectors = TrieMap[String, SinkConnector]()
  
  /**
   * 注册 Source Connector
   * 
   * @param connector Source Connector 实例
   */
  def registerSource(connector: SourceConnector): Unit = {
    val name = connector.name.toLowerCase
    sourceConnectors.put(name, connector) match {
      case Some(existing) =>
        logger.warn(s"Replacing existing source connector: $name (${existing.version} -> ${connector.version})")
      case None =>
        logger.info(s"Registered source connector: $name (version ${connector.version})")
    }
  }
  
  /**
   * 注册 Sink Connector
   * 
   * @param connector Sink Connector 实例
   */
  def registerSink(connector: SinkConnector): Unit = {
    val name = connector.name.toLowerCase
    sinkConnectors.put(name, connector) match {
      case Some(existing) =>
        logger.warn(s"Replacing existing sink connector: $name (${existing.version} -> ${connector.version})")
      case None =>
        logger.info(s"Registered sink connector: $name (version ${connector.version})")
    }
  }
  
  /**
   * 获取 Source Connector
   * 
   * @param name Connector 名称（不区分大小写）
   * @return Source Connector 实例
   * @throws IllegalArgumentException 如果 Connector 不存在
   */
  def getSource(name: String): SourceConnector = {
    sourceConnectors.get(name.toLowerCase) match {
      case Some(connector) => connector
      case None =>
        throw new IllegalArgumentException(
          s"Source connector '$name' not found. Available connectors: ${listSources().mkString(", ")}"
        )
    }
  }
  
  /**
   * 获取 Sink Connector
   * 
   * @param name Connector 名称（不区分大小写）
   * @return Sink Connector 实例
   * @throws IllegalArgumentException 如果 Connector 不存在
   */
  def getSink(name: String): SinkConnector = {
    sinkConnectors.get(name.toLowerCase) match {
      case Some(connector) => connector
      case None =>
        throw new IllegalArgumentException(
          s"Sink connector '$name' not found. Available connectors: ${listSinks().mkString(", ")}"
        )
    }
  }
  
  /**
   * 列出所有可用的 Source Connector
   * 
   * @return Connector 名称列表
   */
  def listSources(): Seq[String] = {
    sourceConnectors.keys.toSeq.sorted
  }
  
  /**
   * 列出所有可用的 Sink Connector
   * 
   * @return Connector 名称列表
   */
  def listSinks(): Seq[String] = {
    sinkConnectors.keys.toSeq.sorted
  }
  
  /**
   * 检查 Source Connector 是否存在
   * 
   * @param name Connector 名称
   * @return 是否存在
   */
  def hasSource(name: String): Boolean = {
    sourceConnectors.contains(name.toLowerCase)
  }
  
  /**
   * 检查 Sink Connector 是否存在
   * 
   * @param name Connector 名称
   * @return 是否存在
   */
  def hasSink(name: String): Boolean = {
    sinkConnectors.contains(name.toLowerCase)
  }
  
  /**
   * 清空所有注册的 Connector（主要用于测试）
   */
  def clear(): Unit = {
    sourceConnectors.clear()
    sinkConnectors.clear()
    logger.info("Cleared all registered connectors")
  }
  
  /**
   * 获取 Connector 信息摘要
   * 
   * @return 包含所有 Connector 信息的 Map
   */
  def getInfo(): Map[String, Any] = {
    Map(
      "sources" -> sourceConnectors.map { case (name, connector) =>
        Map(
          "name" -> name,
          "version" -> connector.version,
          "features" -> connector.supportedFeatures().map(_.toString)
        )
      }.toSeq,
      "sinks" -> sinkConnectors.map { case (name, connector) =>
        Map(
          "name" -> name,
          "version" -> connector.version,
          "features" -> connector.supportedFeatures().map(_.toString)
        )
      }.toSeq
    )
  }
}
