package cn.xuyinyin.cdc.worker

import cn.xuyinyin.cdc.coordinator.OffsetCoordinator
import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent, Delete, Insert, Update}
import cn.xuyinyin.cdc.sink.MySQLSink
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * 默认的 Apply Worker 实现
 * 负责将变更事件批量应用到目标数据库
 * 
 * @param partition 分区号
 * @param sink MySQL Sink
 * @param offsetCoordinator 偏移量协调器
 * @param batchSize 批处理大小
 */
class DefaultApplyWorker(
  partition: Int,
  sink: MySQLSink,
  offsetCoordinator: OffsetCoordinator,
  batchSize: Int = 100
)(implicit ec: ExecutionContext) extends ApplyWorker with LazyLogging {

  override def apply(events: Seq[ChangeEvent]): Future[ApplyResult] = {
    if (events.isEmpty) {
      return Future.successful(ApplyResult(0, Seq.empty, events.lastOption.map(_.position).getOrElse(
        cn.xuyinyin.cdc.model.FilePosition("", 0)
      )))
    }
    
    logger.debug(s"Partition $partition: Applying ${events.size} events")
    
    // 批量处理事件
    val batches = events.grouped(batchSize).toSeq
    
    processBatches(batches, 0, Seq.empty, events.head.position)
  }
  
  private def processBatches(
    batches: Seq[Seq[ChangeEvent]],
    successCount: Int,
    failedEvents: Seq[(ChangeEvent, Throwable)],
    lastPosition: BinlogPosition
  ): Future[ApplyResult] = {
    
    if (batches.isEmpty) {
      return Future.successful(ApplyResult(successCount, failedEvents, lastPosition))
    }
    
    val batch = batches.head
    val remainingBatches = batches.tail
    
    // 处理当前批次
    processBatch(batch).flatMap { case (batchSuccess, batchFailed, batchLastPosition) =>
      // 更新偏移量状态
      if (batchSuccess > 0) {
        batch.foreach { event =>
          offsetCoordinator.markApplied(partition, event.position)
        }
      }
      
      // 递归处理剩余批次
      processBatches(
        remainingBatches,
        successCount + batchSuccess,
        failedEvents ++ batchFailed,
        batchLastPosition
      )
    }
  }
  
  private def processBatch(
    batch: Seq[ChangeEvent]
  ): Future[(Int, Seq[(ChangeEvent, Throwable)], BinlogPosition)] = {
    
    // 并行处理批次中的所有事件
    val futures = batch.map { event =>
      applyEvent(event).map { _ =>
        (Some(event), None)
      }.recover { case ex =>
        logger.error(s"Failed to apply event: ${event.tableId}, operation: ${event.operation}", ex)
        (None, Some((event, ex)))
      }
    }
    
    Future.sequence(futures).map { results =>
      val successEvents = results.flatMap(_._1)
      val failedEvents = results.flatMap(_._2)
      val lastPosition = batch.lastOption.map(_.position).getOrElse(
        cn.xuyinyin.cdc.model.FilePosition("", 0)
      )
      
      (successEvents.size, failedEvents, lastPosition)
    }
  }
  
  private def applyEvent(event: ChangeEvent): Future[Unit] = {
    event.operation match {
      case Insert =>
        applyInsert(event)
        
      case Update =>
        applyUpdate(event)
        
      case Delete =>
        applyDelete(event)
    }
  }
  
  private def applyInsert(event: ChangeEvent): Future[Unit] = {
    event.after match {
      case Some(data) =>
        sink.executeInsert(event.tableId, data).andThen {
          case Success(_) =>
            logger.debug(s"Applied INSERT: ${event.tableId}, pk: ${event.primaryKey}")
          case Failure(ex) =>
            logger.error(s"Failed INSERT: ${event.tableId}, pk: ${event.primaryKey}", ex)
        }
      case None =>
        Future.failed(new IllegalStateException("INSERT event must have 'after' data"))
    }
  }
  
  private def applyUpdate(event: ChangeEvent): Future[Unit] = {
    event.after match {
      case Some(data) =>
        sink.executeUpdate(event.tableId, event.primaryKey, data).andThen {
          case Success(_) =>
            logger.debug(s"Applied UPDATE: ${event.tableId}, pk: ${event.primaryKey}")
          case Failure(ex) =>
            logger.error(s"Failed UPDATE: ${event.tableId}, pk: ${event.primaryKey}", ex)
        }
      case None =>
        Future.failed(new IllegalStateException("UPDATE event must have 'after' data"))
    }
  }
  
  private def applyDelete(event: ChangeEvent): Future[Unit] = {
    sink.executeDelete(event.tableId, event.primaryKey).andThen {
      case Success(_) =>
        logger.debug(s"Applied DELETE: ${event.tableId}, pk: ${event.primaryKey}")
      case Failure(ex) =>
        logger.error(s"Failed DELETE: ${event.tableId}, pk: ${event.primaryKey}", ex)
    }
  }
}

object DefaultApplyWorker {
  /**
   * 创建 Apply Worker 实例
   */
  def apply(
    partition: Int,
    sink: MySQLSink,
    offsetCoordinator: OffsetCoordinator,
    batchSize: Int = 100
  )(implicit ec: ExecutionContext): DefaultApplyWorker = {
    new DefaultApplyWorker(partition, sink, offsetCoordinator, batchSize)
  }
}
