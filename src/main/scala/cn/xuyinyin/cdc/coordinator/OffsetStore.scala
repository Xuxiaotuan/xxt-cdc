package cn.xuyinyin.cdc.coordinator

import cn.xuyinyin.cdc.model.BinlogPosition

import scala.concurrent.Future

/**
 * 偏移量存储接口
 * 提供偏移量的持久化存储
 */
trait OffsetStore {
  /**
   * 保存偏移量
   * 
   * @param position binlog 位置
   * @return 保存结果
   */
  def save(position: BinlogPosition): Future[Unit]
  
  /**
   * 加载最后保存的偏移量
   * 
   * @return 偏移量，如果不存在则返回 None
   */
  def load(): Future[Option[BinlogPosition]]
  
  /**
   * 删除偏移量
   * 
   * @return 删除结果
   */
  def delete(): Future[Unit]
}
