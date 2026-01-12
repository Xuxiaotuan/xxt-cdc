package cn.xuyinyin.cdc.connector.source.mysql

import cn.xuyinyin.cdc.catalog.{CatalogService, MySQLCatalogService}
import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.connector.{ConnectorFeature, SourceConnector, TypeMapper}
import cn.xuyinyin.cdc.normalizer.{EventNormalizer, MySQLEventNormalizer}
import cn.xuyinyin.cdc.reader.{BinlogReader, MySQLBinlogReader}
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

/**
 * MySQL Source Connector 实现
 * 
 * 封装了从 MySQL 读取 CDC 事件所需的所有组件
 */
class MySQLSourceConnector extends SourceConnector {
  
  override def name: String = "mysql"
  
  override def version: String = "1.0.0"
  
  override def createReader(
    config: DatabaseConfig
  )(implicit mat: Materializer, ec: ExecutionContext): BinlogReader = {
    MySQLBinlogReader(config)
  }
  
  override def createCatalog(
    config: DatabaseConfig
  )(implicit ec: ExecutionContext): CatalogService = {
    MySQLCatalogService(config)
  }
  
  override def createNormalizer(
    catalog: CatalogService,
    sourceDatabase: String
  )(implicit ec: ExecutionContext): EventNormalizer = {
    MySQLEventNormalizer(catalog, sourceDatabase)
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
  
  override def supportedFeatures(): Set[ConnectorFeature] = Set(
    ConnectorFeature.IncrementalSync,
    ConnectorFeature.FullSnapshot,
    ConnectorFeature.DDLCapture,
    ConnectorFeature.GTIDSupport,
    ConnectorFeature.ParallelSnapshot
  )
}

object MySQLSourceConnector {
  def apply(): MySQLSourceConnector = new MySQLSourceConnector()
}
