package cn.xuyinyin.cdc.router

import cn.xuyinyin.cdc.model.ChangeEvent

/**
 * 事件路由器接口
 * 将事件路由到不同的分区，保证同表同主键的事件进入同一分区
 */
trait EventRouter {
  /**
   * 计算事件应该路由到的分区号
   * 
   * @param event 变更事件
   * @return 分区号（0 到 partitionCount-1）
   */
  def route(event: ChangeEvent): Int
}

/**
 * 基于哈希的路由器实现
 * 使用 hash(table + pk) % partitionCount 算法
 * 
 * @param partitionCount 分区数量
 */
class HashBasedRouter(partitionCount: Int) extends EventRouter {
  require(partitionCount > 0, "Partition count must be positive")
  
  override def route(event: ChangeEvent): Int = {
    val key = s"${event.tableId}:${event.primaryKey.hashCode()}"
    Math.abs(key.hashCode()) % partitionCount
  }
}
