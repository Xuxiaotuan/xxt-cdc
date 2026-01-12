package cn.xuyinyin.cdc.connector

/**
 * Connector 特定配置
 * 
 * 允许每个 Connector 定义自己的配置选项，而不是所有配置都放在 DatabaseConfig 中
 */
case class ConnectorConfig(
  /**
   * Connector 特定的配置项
   * 例如：
   * - MySQL: Map("serverTimezone" -> "UTC", "useSSL" -> "false")
   * - StarRocks: Map("streamLoadUrl" -> "http://fe:8030", "batchSize" -> "10000")
   * - PostgreSQL: Map("replication" -> "database", "slotName" -> "cdc_slot")
   */
  properties: Map[String, String] = Map.empty
) {
  
  /**
   * 获取配置项
   */
  def get(key: String): Option[String] = properties.get(key)
  
  /**
   * 获取配置项，如果不存在则返回默认值
   */
  def getOrElse(key: String, default: String): String = properties.getOrElse(key, default)
  
  /**
   * 获取整数配置项
   */
  def getInt(key: String): Option[Int] = properties.get(key).flatMap(v => scala.util.Try(v.toInt).toOption)
  
  /**
   * 获取整数配置项，如果不存在则返回默认值
   */
  def getIntOrElse(key: String, default: Int): Int = getInt(key).getOrElse(default)
  
  /**
   * 获取布尔配置项
   */
  def getBoolean(key: String): Option[Boolean] = properties.get(key).flatMap(v => scala.util.Try(v.toBoolean).toOption)
  
  /**
   * 获取布尔配置项，如果不存在则返回默认值
   */
  def getBooleanOrElse(key: String, default: Boolean): Boolean = getBoolean(key).getOrElse(default)
  
  /**
   * 添加配置项
   */
  def withProperty(key: String, value: String): ConnectorConfig = {
    copy(properties = properties + (key -> value))
  }
  
  /**
   * 添加多个配置项
   */
  def withProperties(props: Map[String, String]): ConnectorConfig = {
    copy(properties = properties ++ props)
  }
}

object ConnectorConfig {
  /**
   * 空配置
   */
  val empty: ConnectorConfig = ConnectorConfig(Map.empty)
  
  /**
   * 从 Map 创建配置
   */
  def apply(properties: Map[String, String]): ConnectorConfig = {
    new ConnectorConfig(properties)
  }
}
