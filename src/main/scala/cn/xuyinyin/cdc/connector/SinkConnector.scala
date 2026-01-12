package cn.xuyinyin.cdc.connector

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.model.TableId

import scala.concurrent.{ExecutionContext, Future}

/**
 * 目标数据库 Connector 接口
 * 
 * 封装了向特定数据库写入数据所需的所有组件：
 * - Writer: 执行 INSERT/UPDATE/DELETE 操作
 * - TypeMapper: 类型映射
 * - 幂等性保证
 * 
 * 每种目标数据库（MySQL、StarRocks等）需要实现此接口
 */
trait SinkConnector {
  /**
   * Connector 名称（如 "mysql", "starrocks"）
   */
  def name: String
  
  /**
   * Connector 版本
   */
  def version: String
  
  /**
   * 创建数据写入器
   * 
   * @param config 数据库配置
   * @param ec 执行上下文
   * @return DataWriter 实例
   */
  def createWriter(
    config: DatabaseConfig
  )(implicit ec: ExecutionContext): DataWriter
  
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
  def supportedFeatures(): Set[SinkFeature] = Set(
    SinkFeature.IdempotentWrite,
    SinkFeature.BatchWrite
  )
}

/**
 * 数据写入器接口
 * 
 * 提供幂等的数据库写入操作，与具体数据库解耦
 */
trait DataWriter {
  /**
   * 执行插入操作（幂等）
   * 
   * @param table 表标识
   * @param data 数据映射（列名 -> 值）
   * @return 操作结果
   */
  def insert(table: TableId, data: Map[String, Any]): Future[Unit]
  
  /**
   * 执行更新操作（幂等）
   * 
   * @param table 表标识
   * @param primaryKey 主键映射（列名 -> 值）
   * @param data 更新数据映射（列名 -> 值）
   * @return 操作结果
   */
  def update(table: TableId, primaryKey: Map[String, Any], data: Map[String, Any]): Future[Unit]
  
  /**
   * 执行删除操作（幂等）
   * 
   * @param table 表标识
   * @param primaryKey 主键映射（列名 -> 值）
   * @return 操作结果
   */
  def delete(table: TableId, primaryKey: Map[String, Any]): Future[Unit]
  
  /**
   * 批量插入（可选实现，用于快照阶段）
   * 
   * @param table 表标识
   * @param rows 数据行列表
   * @return 操作结果
   */
  def batchInsert(table: TableId, rows: Seq[Map[String, Any]]): Future[Unit] = {
    // 默认实现：逐行插入
    import scala.concurrent.ExecutionContext.Implicits.global
    Future.sequence(rows.map(row => insert(table, row))).map(_ => ())
  }
  
  /**
   * 关闭写入器，释放资源
   */
  def close(): Unit
}

/**
 * Sink 功能枚举
 */
sealed trait SinkFeature

object SinkFeature {
  /** 幂等写入 */
  case object IdempotentWrite extends SinkFeature
  
  /** 批量写入 */
  case object BatchWrite extends SinkFeature
  
  /** 事务支持 */
  case object TransactionSupport extends SinkFeature
  
  /** Upsert 支持 */
  case object UpsertSupport extends SinkFeature
  
  /** DDL 同步 */
  case object DDLSync extends SinkFeature
}
