package cn.xuyinyin.cdc.worker

import cn.xuyinyin.cdc.connector.DataWriter
import cn.xuyinyin.cdc.coordinator.OffsetCoordinator
import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent, Delete, Insert, Update}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * 默认的 Apply Worker 实现
 * 负责将变更事件批量应用到目标数据库
 * 
 * 使用 DataWriter 接口，支持任意目标数据库（MySQL、StarRocks 等）
 * 
 * @param partition 分区号
 * @param writer 数据写入器（支持任意数据库）
 * @param offsetCoordinator 偏移量协调器
 * @param batchSize 批处理大小
 * @param metrics CDC 指标收集器
 */
class DefaultApplyWorker(
  partition: Int,
  writer: DataWriter,
  offsetCoordinator: OffsetCoordinator,
  batchSize: Int = 100,
  metrics: Option[cn.xuyinyin.cdc.metrics.CDCMetrics] = None
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
    
    // 同 partition 内严格串行 apply，保证同主键顺序
    // Hash Router 保证同主键进同一 partition，这里不能再并发破坏顺序
    batch.foldLeft(Future.successful((0, Seq.empty[(ChangeEvent, Throwable)], batch.last.position))) {
      case (accFuture, event) =>
        accFuture.flatMap { case (successCount, failedEvents, lastPos) =>
          applyEvent(event).map { _ =>
            (successCount + 1, failedEvents, event.position)
          }.recover { case ex =>
            logger.error(s"Failed to apply event: ${event.tableId}, operation: ${event.operation}", ex)
            (successCount, failedEvents :+ (event, ex), event.position)
          }
        }
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
        writer.insert(event.tableId, data).andThen {
          case Success(_) =>
            // 记录指标
            metrics.foreach(_.recordApply(1))
            // 输出详细日志
            logger.info(s"✓ INSERT: ${event.tableId} | PK: ${event.primaryKey} | Data: ${formatData(data)}")
          case Failure(ex) =>
            metrics.foreach(_.recordError())
            logger.error(s"✗ INSERT FAILED: ${event.tableId} | PK: ${event.primaryKey}", ex)
        }
      case None =>
        Future.failed(new IllegalStateException("INSERT event must have 'after' data"))
    }
  }
  
  private def applyUpdate(event: ChangeEvent): Future[Unit] = {
    event.after match {
      case Some(data) =>
        writer.update(event.tableId, event.primaryKey, data).andThen {
          case Success(_) =>
            // 记录指标
            metrics.foreach(_.recordApply(1))
            // 输出详细日志 - 显示 before 和 after
            val changeDetails = event.before match {
              case Some(before) =>
                // 找出变更的字段
                val changedFields = data.filter { case (k, v) => 
                  before.get(k) match {
                    case Some(oldValue) => oldValue != v
                    case None => true  // 新增的字段
                  }
                }
                if (changedFields.nonEmpty) {
                  val changes = changedFields.map { case (k, newValue) =>
                    val oldValue = before.getOrElse(k, "NULL")
                    s"$k: $oldValue → $newValue"
                  }.mkString(", ")
                  s"Changes: $changes"
                } else {
                  s"Data: ${formatData(data)}"
                }
              case None =>
                s"New: ${formatData(data)}"
            }
            logger.info(s"✓ UPDATE: ${event.tableId} | PK: ${event.primaryKey} | $changeDetails")
          case Failure(ex) =>
            metrics.foreach(_.recordError())
            logger.error(s"✗ UPDATE FAILED: ${event.tableId} | PK: ${event.primaryKey}", ex)
        }
      case None =>
        Future.failed(new IllegalStateException("UPDATE event must have 'after' data"))
    }
  }
  
  private def applyDelete(event: ChangeEvent): Future[Unit] = {
    writer.delete(event.tableId, event.primaryKey).andThen {
      case Success(_) =>
        // 记录指标
        metrics.foreach(_.recordApply(1))
        // 输出详细日志
        logger.info(s"✓ DELETE: ${event.tableId} | PK: ${event.primaryKey}")
      case Failure(ex) =>
        metrics.foreach(_.recordError())
        logger.error(s"✗ DELETE FAILED: ${event.tableId} | PK: ${event.primaryKey}", ex)
    }
  }
  
  /**
   * 格式化数据用于日志输出
   * 限制输出长度，避免日志过长
   */
  private def formatData(data: Map[String, Any]): String = {
    val formatted = data.map { case (k, v) =>
      val valueStr = v match {
        case s: String if s.length > 50 => s.take(50) + "..."
        case other => String.valueOf(other)
      }
      s"$k=$valueStr"
    }.mkString(", ")
    
    if (formatted.length > 200) {
      formatted.take(200) + "..."
    } else {
      formatted
    }
  }
}

object DefaultApplyWorker {
  /**
   * 创建 Apply Worker 实例
   */
  def apply(
    partition: Int,
    writer: DataWriter,
    offsetCoordinator: OffsetCoordinator,
    batchSize: Int = 100,
    metrics: Option[cn.xuyinyin.cdc.metrics.CDCMetrics] = None
  )(implicit ec: ExecutionContext): DefaultApplyWorker = {
    new DefaultApplyWorker(partition, writer, offsetCoordinator, batchSize, metrics)
  }
}
