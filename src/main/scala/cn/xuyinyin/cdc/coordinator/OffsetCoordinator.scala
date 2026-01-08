package cn.xuyinyin.cdc.coordinator

import cn.xuyinyin.cdc.model.BinlogPosition

import scala.concurrent.Future

/**
 * 偏移量协调器接口
 * 管理偏移量的状态转换：RECEIVED → APPLIED → COMMITTED
 */
trait OffsetCoordinator {
  /**
   * 标记偏移量为 RECEIVED 状态
   * 当 Binlog Reader 读取到事件时调用
   * 
   * @param position binlog 位置
   */
  def markReceived(position: BinlogPosition): Unit
  
  /**
   * 标记偏移量为 APPLIED 状态
   * 当 Apply Worker 成功应用事件到目标库时调用
   * 
   * @param partition 分区号
   * @param position binlog 位置
   */
  def markApplied(partition: Int, position: BinlogPosition): Unit
  
  /**
   * 获取可以提交的偏移量位置
   * 只有当所有分区对某个偏移量之前的事件都 APPLIED 时，该偏移量才可提交
   * 
   * @return 可提交的偏移量，如果没有则返回 None
   */
  def getCommittablePosition(): Option[BinlogPosition]
  
  /**
   * 提交偏移量为 COMMITTED 状态
   * 将偏移量持久化到存储
   * 
   * @param position binlog 位置
   * @return 提交结果
   */
  def commit(position: BinlogPosition): Future[Unit]
  
  /**
   * 获取最后已提交的偏移量
   * 用于系统重启时恢复
   * 
   * @return 最后已提交的偏移量
   */
  def getLastCommittedPosition(): Option[BinlogPosition]
}
