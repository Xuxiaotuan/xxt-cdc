package cn.xuyinyin.cdc.worker

import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent}

import scala.concurrent.Future

/**
 * Apply Worker 接口
 * 负责将变更事件应用到目标数据库
 */
trait ApplyWorker {
  /**
   * 应用一批变更事件
   * 
   * @param events 变更事件列表
   * @return 应用结果
   */
  def apply(events: Seq[ChangeEvent]): Future[ApplyResult]
}

/**
 * 应用结果
 * 
 * @param successCount 成功应用的事件数量
 * @param failedEvents 失败的事件及其异常
 * @param lastAppliedPosition 最后成功应用的 binlog 位置
 */
case class ApplyResult(
  successCount: Int,
  failedEvents: Seq[(ChangeEvent, Throwable)],
  lastAppliedPosition: BinlogPosition
)
