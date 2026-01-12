package cn.xuyinyin.cdc.connector.sink.mysql

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.connector.jdbc.{JdbcConnectionManager, JdbcDataWriter}
import cn.xuyinyin.cdc.connector.{ConnectorConfig, DataWriter, SinkConnector, SinkFeature, TypeMapper}
import cn.xuyinyin.cdc.model.TableId

import scala.concurrent.ExecutionContext

/**
 * MySQL Sink Connector 实现
 * 
 * 封装了向 MySQL 写入数据所需的所有组件
 */
class MySQLSinkConnector extends SinkConnector {
  
  override def name: String = "mysql"
  
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
    val connectionManager = JdbcConnectionManager.forMySQL(config, connectorConfig)
    new MySQLDataWriter(connectionManager, config.database, connectorConfig)
  }
  
  override def getTypeMapper(): TypeMapper = {
    MySQLTypeMapper()
  }
  
  override def validateConfig(config: DatabaseConfig): Option[String] = {
    // 验证 MySQL 配置
    if (config.host.isEmpty) {
      return Some("MySQL host cannot be empty")
    }
    if (config.port <= 0 || config.port > 65535) {
      return Some(s"Invalid MySQL port: ${config.port}")
    }
    if (config.username.isEmpty) {
      return Some("MySQL username cannot be empty")
    }
    if (config.database.isEmpty) {
      return Some("MySQL database cannot be empty")
    }
    None
  }
  
  override def supportedFeatures(): Set[SinkFeature] = Set(
    SinkFeature.IdempotentWrite,
    SinkFeature.BatchWrite,
    SinkFeature.TransactionSupport,
    SinkFeature.UpsertSupport,
    SinkFeature.DDLSync
  )
}

/**
 * MySQL 数据写入器实现
 * 
 * 使用 JDBC 基类，实现 MySQL 特定的 SQL 构建逻辑
 */
class MySQLDataWriter(
  connectionManager: JdbcConnectionManager,
  targetDatabase: String,
  connectorConfig: ConnectorConfig
)(implicit ec: ExecutionContext) extends JdbcDataWriter(connectionManager, connectorConfig) {
  
  override protected def getTargetDatabase(): Option[String] = Some(targetDatabase)
  
  override protected def buildInsertSql(table: TableId, columns: Seq[String]): String = {
    val fullTableName = getFullTableName(table)
    val columnList = columns.mkString(", ")
    val placeholders = columns.map(_ => "?").mkString(", ")
    val updateClause = columns.map(col => s"$col = VALUES($col)").mkString(", ")
    
    s"""INSERT INTO $fullTableName ($columnList)
       |VALUES ($placeholders)
       |ON DUPLICATE KEY UPDATE $updateClause""".stripMargin
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
  
  override protected def getInsertParameters(data: Map[String, Any], columns: Seq[String]): Seq[Any] = {
    // MySQL 的 ON DUPLICATE KEY UPDATE 需要两份参数
    val values = columns.map(data)
    values ++ values
  }
}

object MySQLSinkConnector {
  def apply(): MySQLSinkConnector = new MySQLSinkConnector()
}
