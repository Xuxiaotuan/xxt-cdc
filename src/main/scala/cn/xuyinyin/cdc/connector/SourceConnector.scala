package cn.xuyinyin.cdc.connector

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.normalizer.EventNormalizer
import cn.xuyinyin.cdc.reader.BinlogReader
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

/**
 * 源数据库 Connector 接口
 * 
 * 封装了从特定数据库读取 CDC 事件所需的所有组件：
 * - Reader: 读取变更日志（binlog/WAL等）
 * - Catalog: 管理表元数据
 * - Normalizer: 将原始事件标准化为通用格式
 * - TypeMapper: 类型映射
 * 
 * 每种数据源（MySQL、PostgreSQL等）需要实现此接口
 */
trait SourceConnector {
  /**
   * Connector 名称（如 "mysql", "postgresql"）
   */
  def name: String
  
  /**
   * Connector 版本
   */
  def version: String
  
  /**
   * 创建变更日志读取器
   * 
   * @param config 数据库配置
   * @param mat Pekko Materializer
   * @param ec 执行上下文
   * @return BinlogReader 实例
   */
  def createReader(
    config: DatabaseConfig
  )(implicit mat: Materializer, ec: ExecutionContext): BinlogReader
  
  /**
   * 创建目录服务
   * 
   * @param config 数据库配置
   * @param ec 执行上下文
   * @return CatalogService 实例
   */
  def createCatalog(
    config: DatabaseConfig
  )(implicit ec: ExecutionContext): CatalogService
  
  /**
   * 创建事件标准化器
   * 
   * @param catalog 目录服务
   * @param sourceDatabase 源数据库名称
   * @param ec 执行上下文
   * @return EventNormalizer 实例
   */
  def createNormalizer(
    catalog: CatalogService,
    sourceDatabase: String
  )(implicit ec: ExecutionContext): EventNormalizer
  
  /**
   * 获取类型映射器
   * 
   * @return TypeMapper 实例
   */
  def getTypeMapper(): TypeMapper
  
  /**
   * 验证配置是否有效
   * 
   * @param config 数据库配置
   * @return 验证结果（成功返回 None，失败返回错误信息）
   */
  def validateConfig(config: DatabaseConfig): Option[String] = None
  
  /**
   * 获取支持的功能
   * 
   * @return 功能集合
   */
  def supportedFeatures(): Set[ConnectorFeature] = Set(
    ConnectorFeature.IncrementalSync,
    ConnectorFeature.FullSnapshot
  )
}

/**
 * Connector 功能枚举
 */
sealed trait ConnectorFeature

object ConnectorFeature {
  /** 增量同步 */
  case object IncrementalSync extends ConnectorFeature
  
  /** 全量快照 */
  case object FullSnapshot extends ConnectorFeature
  
  /** DDL 变更捕获 */
  case object DDLCapture extends ConnectorFeature
  
  /** GTID 支持 */
  case object GTIDSupport extends ConnectorFeature
  
  /** 并行快照 */
  case object ParallelSnapshot extends ConnectorFeature
}
