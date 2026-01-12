package cn.xuyinyin.cdc.connector.sink.starrocks

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.connector.jdbc.{JdbcConnectionManager, JdbcDataWriter}
import cn.xuyinyin.cdc.connector.{ConnectorConfig, DataWriter, SinkConnector, SinkFeature, TypeMapper}
import cn.xuyinyin.cdc.model.TableId

import scala.concurrent.ExecutionContext

/**
 * StarRocks Sink Connector 实现
 * 
 * 支持向 StarRocks 写入数据，使用 Primary Key 表模型实现幂等写入
 */
class StarRocksSinkConnector extends SinkConnector {
  
  override def name: String = "starrocks"
  
  override def version: String = "1.0.0"
  
  override def createWriter(
    config: DatabaseConfig
  )(implicit ec: ExecutionContext): DataWriter = {
    createWriter(config, ConnectorConfig.empty)
  }
  
  /**
   * 创建写入器（支持自定义配置）
   */
  def createWriter(
    config: DatabaseConfig,
    connectorConfig: ConnectorConfig
  )(implicit ec: ExecutionContext): DataWriter = {
    val connectionManager = JdbcConnectionManager.forStarRocks(config, connectorConfig)
    new StarRocksDataWriter(connectionManager, config.database, connectorConfig)
  }
  
  override def getTypeMapper(): TypeMapper = {
    StarRocksTypeMapper()
  }
  
  override def validateConfig(config: DatabaseConfig): Option[String] = {
    if (config.host.isEmpty) {
      return Some("StarRocks host cannot be empty")
    }
    if (config.port <= 0 || config.port > 65535) {
      return Some(s"Invalid StarRocks port: ${config.port}")
    }
    if (config.username.isEmpty) {
      return Some("StarRocks username cannot be empty")
    }
    if (config.database.isEmpty) {
      return Some("StarRocks database cannot be empty")
    }
    None
  }
  
  override def supportedFeatures(): Set[SinkFeature] = Set(
    SinkFeature.IdempotentWrite,
    SinkFeature.BatchWrite,
    SinkFeature.UpsertSupport
  )
}

/**
 * StarRocks 数据写入器实现
 * 
 * 使用 JDBC 基类，实现 StarRocks 特定的 SQL 构建逻辑
 * StarRocks Primary Key 表自动支持 UPSERT 语义
 */
class StarRocksDataWriter(
  connectionManager: JdbcConnectionManager,
  targetDatabase: String,
  connectorConfig: ConnectorConfig
)(implicit ec: ExecutionContext) extends JdbcDataWriter(connectionManager, connectorConfig) {
  
  override protected def getTargetDatabase(): Option[String] = Some(targetDatabase)
  
  override protected def buildInsertSql(table: TableId, columns: Seq[String]): String = {
    val fullTableName = getFullTableName(table)
    val columnList = columns.mkString(", ")
    val placeholders = columns.map(_ => "?").mkString(", ")
    
    // StarRocks Primary Key 表：INSERT 自动支持 UPSERT
    s"""INSERT INTO $fullTableName ($columnList)
       |VALUES ($placeholders)""".stripMargin
  }
  
  override protected def buildUpdateSql(table: TableId, pkColumns: Seq[String], dataColumns: Seq[String]): String = {
    val fullTableName = getFullTableName(table)
    val setClause = dataColumns.map(col => s"$col = ?").mkString(", ")
    val whereClause = pkColumns.map(col => s"$col = ?").mkString(" AND ")
    
    s"""UPDATE $fullTableName
       |SET $setClause
       |WHERE $whereClause""".stripMargin
  }
  
  override protected def buildDeleteSql(table: TableId, pkColumns: Seq[String]): String = {
    val fullTableName = getFullTableName(table)
    val whereClause = pkColumns.map(col => s"$col = ?").mkString(" AND ")
    
    s"""DELETE FROM $fullTableName
       |WHERE $whereClause""".stripMargin
  }
}

object StarRocksSinkConnector {
  def apply(): StarRocksSinkConnector = new StarRocksSinkConnector()
}
