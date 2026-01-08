package cn.xuyinyin.cdc.catalog

import cn.xuyinyin.cdc.config.{DatabaseConfig, FilterConfig}
import cn.xuyinyin.cdc.model.{TableId, TableMeta, TableSchema}

import scala.concurrent.Future

/**
 * Catalog 服务接口
 * 负责表发现、元数据管理和过滤规则处理
 */
trait CatalogService {
  /**
   * 发现符合过滤规则的表
   * 
   * @param config 过滤配置
   * @return 表元数据列表
   */
  def discoverTables(config: FilterConfig): Future[Seq[TableMeta]]
  
  /**
   * 获取表结构信息
   * 
   * @param table 表标识
   * @return 表结构
   */
  def getTableSchema(table: TableId): Future[TableSchema]
  
  /**
   * 验证源数据库的 binlog 配置
   * 
   * @param source 数据库配置
   * @return binlog 能力信息
   */
  def validateBinlogConfig(source: DatabaseConfig): Future[BinlogCapability]
}

/**
 * Binlog 能力信息
 * 
 * @param enabled binlog 是否启用
 * @param format binlog 格式（ROW/STATEMENT/MIXED）
 * @param rowImage 行镜像格式（FULL/MINIMAL/NOBLOB）
 * @param gtidEnabled GTID 是否启用
 */
case class BinlogCapability(
  enabled: Boolean,
  format: String,
  rowImage: String,
  gtidEnabled: Boolean
)
