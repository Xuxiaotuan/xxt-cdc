package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.util.Timeout

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * 快照管理器
 * 负责协调整个快照过程，包括调度、监控和状态管理
 */
class SnapshotManager(
  catalogService: CatalogService,
  scheduler: ActorRef[SnapshotSchedulerMessage],
  config: SnapshotSchedulerConfig
)(implicit system: ActorSystem[_]) extends LazyLogging {
  
  implicit val timeout: Timeout = 30.seconds
  implicit val ec: ExecutionContext = system.executionContext
  
  /**
   * 为指定表启动快照
   */
  def startSnapshot(
    tableId: TableId,
    lowWatermark: BinlogPosition
  ): Future[String] = {
    logger.info(s"Starting snapshot for table $tableId with low watermark $lowWatermark")
    
    // 验证表是否存在
    catalogService.getTableSchema(tableId).flatMap { schema =>
      logger.debug(s"Table $tableId schema: ${schema.columns.size} columns")
      
      // 调度快照任务
      scheduler.ask[SnapshotSchedulerResponse](ref => ScheduleSnapshot(tableId, lowWatermark, ref))
        .map {
          case SnapshotScheduled(taskId) =>
            logger.info(s"Snapshot scheduled for table $tableId with task ID $taskId")
            taskId
          case SnapshotError(message) =>
            throw new RuntimeException(s"Failed to schedule snapshot: $message")
          case other =>
            throw new RuntimeException(s"Unexpected response: $other")
        }
    }.recoverWith { case ex =>
      Future.failed(new RuntimeException(s"Table $tableId not found or error occurred", ex))
    }
  }
  
  /**
   * 为多个表启动快照
   */
  def startSnapshotBatch(
    tables: Seq[TableId],
    lowWatermark: BinlogPosition
  ): Future[Map[TableId, String]] = {
    logger.info(s"Starting batch snapshot for ${tables.size} tables")
    
    val futures = tables.map { tableId =>
      startSnapshot(tableId, lowWatermark).map(taskId => tableId -> taskId)
    }
    
    Future.sequence(futures).map(_.toMap)
  }
  
  /**
   * 获取快照任务状态
   */
  def getSnapshotStatus(taskId: String): Future[SnapshotTask] = {
    scheduler.ask[SnapshotSchedulerResponse](ref => GetSnapshotStatus(taskId, ref))
      .map {
        case SnapshotTaskStatusResponse(task) => task
        case SnapshotError(message) =>
          throw new RuntimeException(s"Failed to get snapshot status: $message")
        case other =>
          throw new RuntimeException(s"Unexpected response: $other")
      }
  }
  
  /**
   * 列出快照任务
   */
  def listSnapshotTasks(tableId: Option[TableId] = None): Future[Seq[SnapshotTask]] = {
    scheduler.ask[SnapshotSchedulerResponse](ref => ListSnapshotTasks(tableId, ref))
      .map {
        case SnapshotTaskList(tasks) => tasks
        case SnapshotError(message) =>
          throw new RuntimeException(s"Failed to list snapshot tasks: $message")
        case other =>
          throw new RuntimeException(s"Unexpected response: $other")
      }
  }
  
  /**
   * 取消快照任务
   */
  def cancelSnapshot(taskId: String): Future[Unit] = {
    scheduler.ask[SnapshotSchedulerResponse](ref => CancelSnapshot(taskId, ref))
      .map {
        case SnapshotCancelled(_) => ()
        case SnapshotError(message) =>
          throw new RuntimeException(s"Failed to cancel snapshot: $message")
        case other =>
          throw new RuntimeException(s"Unexpected response: $other")
      }
  }
  
  /**
   * 等待快照完成
   */
  def waitForSnapshotCompletion(
    taskId: String,
    maxWaitTime: FiniteDuration = 1.hour,
    pollInterval: FiniteDuration = 5.seconds
  ): Future[SnapshotTask] = {
    
    def poll(startTime: Instant): Future[SnapshotTask] = {
      if (Instant.now().isAfter(startTime.plusMillis(maxWaitTime.toMillis))) {
        Future.failed(new RuntimeException(s"Timeout waiting for snapshot $taskId to complete"))
      } else {
        getSnapshotStatus(taskId).flatMap { task =>
          if (task.isCompleted) {
            logger.info(s"Snapshot $taskId completed successfully with ${task.rowCount} rows")
            Future.successful(task)
          } else if (task.isFailed) {
            val errorMsg = task.errorMessage.getOrElse("Unknown error")
            Future.failed(new RuntimeException(s"Snapshot $taskId failed: $errorMsg"))
          } else {
            // 继续轮询
            Future {
              Thread.sleep(pollInterval.toMillis)
            }.flatMap(_ => poll(startTime))
          }
        }
      }
    }
    
    poll(Instant.now())
  }
  
  /**
   * 等待批量快照完成
   */
  def waitForSnapshotBatchCompletion(
    taskIds: Seq[String],
    maxWaitTime: FiniteDuration = 2.hours,
    pollInterval: FiniteDuration = 10.seconds
  ): Future[Map[String, SnapshotTask]] = {
    
    def pollBatch(startTime: Instant): Future[Map[String, SnapshotTask]] = {
      if (Instant.now().isAfter(startTime.plusMillis(maxWaitTime.toMillis))) {
        Future.failed(new RuntimeException(s"Timeout waiting for batch snapshots to complete"))
      } else {
        val statusFutures = taskIds.map { taskId =>
          getSnapshotStatus(taskId).map(taskId -> _)
        }
        
        Future.sequence(statusFutures).flatMap { taskStatuses =>
          val taskMap = taskStatuses.toMap
          val completed = taskMap.values.count(_.isCompleted)
          val failed = taskMap.values.count(_.isFailed)
          val running = taskMap.values.count(_.isRunning)
          val pending = taskMap.values.count(_.isPending)
          
          logger.info(s"Batch snapshot progress: $completed completed, $failed failed, $running running, $pending pending")
          
          if (taskMap.values.forall(task => task.isCompleted || task.isFailed)) {
            val failedTasks = taskMap.filter(_._2.isFailed)
            if (failedTasks.nonEmpty) {
              val failedTaskIds = failedTasks.keys.mkString(", ")
              Future.failed(new RuntimeException(s"Some snapshots failed: $failedTaskIds"))
            } else {
              logger.info(s"All ${taskIds.size} snapshots completed successfully")
              Future.successful(taskMap)
            }
          } else {
            // 继续轮询
            Future {
              Thread.sleep(pollInterval.toMillis)
            }.flatMap(_ => pollBatch(startTime))
          }
        }
      }
    }
    
    pollBatch(Instant.now())
  }
  
  /**
   * 获取快照统计信息
   */
  def getSnapshotStatistics(): Future[SnapshotStatistics] = {
    listSnapshotTasks().map { tasks =>
      val totalTasks = tasks.size
      val completedTasks = tasks.count(_.isCompleted)
      val failedTasks = tasks.count(_.isFailed)
      val runningTasks = tasks.count(_.isRunning)
      val pendingTasks = tasks.count(_.isPending)
      
      val totalRows = tasks.filter(_.isCompleted).map(_.rowCount).sum
      val avgRowsPerTask = if (completedTasks > 0) totalRows / completedTasks else 0
      
      val completedTasksWithDuration = tasks.filter(task => 
        task.isCompleted && task.startedAt.isDefined && task.completedAt.isDefined
      )
      
      val avgDuration = if (completedTasksWithDuration.nonEmpty) {
        val totalDuration = completedTasksWithDuration.map { task =>
          task.completedAt.get.toEpochMilli - task.startedAt.get.toEpochMilli
        }.sum
        totalDuration / completedTasksWithDuration.size
      } else 0L
      
      SnapshotStatistics(
        totalTasks = totalTasks,
        completedTasks = completedTasks,
        failedTasks = failedTasks,
        runningTasks = runningTasks,
        pendingTasks = pendingTasks,
        totalRows = totalRows,
        avgRowsPerTask = avgRowsPerTask,
        avgDurationMs = avgDuration
      )
    }
  }
  
  /**
   * 清理已完成的快照任务
   */
  def cleanupCompletedTasks(olderThan: FiniteDuration = 24.hours): Future[Int] = {
    val cutoffTime = Instant.now().minusMillis(olderThan.toMillis)
    
    listSnapshotTasks().map { tasks =>
      val tasksToCleanup = tasks.filter { task =>
        (task.isCompleted || task.isFailed) && 
        task.completedAt.exists(_.isBefore(cutoffTime))
      }
      
      logger.info(s"Found ${tasksToCleanup.size} tasks to cleanup")
      
      // 在实际实现中，这里应该从调度器中删除这些任务
      // 目前只是返回计数
      tasksToCleanup.size
    }
  }
}

/**
 * 快照统计信息
 */
case class SnapshotStatistics(
  totalTasks: Int,
  completedTasks: Int,
  failedTasks: Int,
  runningTasks: Int,
  pendingTasks: Int,
  totalRows: Long,
  avgRowsPerTask: Long,
  avgDurationMs: Long
) {
  def successRate: Double = {
    if (totalTasks > 0) completedTasks.toDouble / totalTasks else 0.0
  }
  
  def toMap: Map[String, Any] = Map(
    "totalTasks" -> totalTasks,
    "completedTasks" -> completedTasks,
    "failedTasks" -> failedTasks,
    "runningTasks" -> runningTasks,
    "pendingTasks" -> pendingTasks,
    "totalRows" -> totalRows,
    "avgRowsPerTask" -> avgRowsPerTask,
    "avgDurationMs" -> avgDurationMs,
    "successRate" -> successRate
  )
}

object SnapshotManager {
  /**
   * 创建快照管理器
   */
  def apply(
    catalogService: CatalogService,
    scheduler: ActorRef[SnapshotSchedulerMessage],
    config: SnapshotSchedulerConfig
  )(implicit system: ActorSystem[_]): SnapshotManager = {
    new SnapshotManager(catalogService, scheduler, config)
  }
}