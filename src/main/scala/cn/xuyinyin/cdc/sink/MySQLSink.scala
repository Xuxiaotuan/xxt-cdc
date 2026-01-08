package cn.xuyinyin.cdc.sink

import cn.xuyinyin.cdc.model.TableId

import scala.concurrent.Future

/**
 * MySQL Sink 接口
 * 提供幂等的数据库写入操作
 */
trait MySQLSink {
  /**
   * 执行插入操作（幂等）
   * 使用 INSERT ... ON DUPLICATE KEY UPDATE 语法
   * 
   * @param table 表标识
   * @param data 数据映射
   * @return 操作结果
   */
  def executeInsert(table: TableId, data: Map[String, Any]): Future[Unit]
  
  /**
   * 执行更新操作（幂等）
   * 基于主键条件更新，不依赖 before 值
   * 
   * @param table 表标识
   * @param pk 主键映射
   * @param data 更新数据映射
   * @return 操作结果
   */
  def executeUpdate(table: TableId, pk: Map[String, Any], data: Map[String, Any]): Future[Unit]
  
  /**
   * 执行删除操作（幂等）
   * 使用主键条件删除，忽略记录不存在错误
   * 
   * @param table 表标识
   * @param pk 主键映射
   * @return 操作结果
   */
  def executeDelete(table: TableId, pk: Map[String, Any]): Future[Unit]
}
