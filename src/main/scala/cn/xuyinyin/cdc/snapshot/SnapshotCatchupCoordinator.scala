package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import cn.xuyinyin.cdc.reader.BinlogReader
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.ActorSystem

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * 快照-Catchup 协调状态
 */
sealed trait SnapshotCatchupStatus
object SnapshotCatchupStatus {
  case object NotStarted extends SnapshotCatchupStatus
  case object SnapshotInProgress extends SnapshotCatchupStatus
  case object CatchupInProgress extends SnapshotCatchupStatus
  case object Completed extends SnapshotCatchupStatus
  case object Failed extends SnapshotCatchupStatus
  case object Cancelled extends SnapshotCatchupStatus
}

/**
 * 快照-Catchup 任务
 */
case class SnapshotCatchupTask(
  id: String,
  tableId: TableId,
  status: SnapshotCatchupStatus = SnapshotCatchupStatus.NotStarted,
  snapshotTaskId: Option[String] = None,
  catchupTaskId: Option[String] = None,
  lowWatermark: Option[BinlogPosition] = None,
  targetPosition: Option[BinlogPosition] = None,
  createdAt: Instant = Instant.now(),
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  snapshotRows: Long = 0,
  catchupEvents: Long = 0,
  errorMessage: Option[String] = None
) {
  def isCompleted: Boolean = status == SnapshotCatchupStatus.Completed
  def isFailed: Boolean = status == SnapshotCatchupStatus.Failed
  def isRunning: Boolean = status == SnapshotCatchupStatus.SnapshotInProgress || status == SnapshotCatchupStatus.CatchupInProgress
  
  def withStatus(newStatus: SnapshotCatchupStatus): SnapshotCatchupTask = {
    val now = Instant.now()
    newStatus match {
      case SnapshotCatchupStatus.SnapshotInProgress if startedAt.isEmpty => 
        copy(status = newStatus, startedAt = Some(now))
      case SnapshotCatchupStatus.Completed | SnapshotCatchupStatus.Failed | SnapshotCatchupStatus.Cancelled => 
        copy(status = newStatus, completedAt = Some(now))
      case _ => copy(status = newStatus)
    }
  }
  
  def withSnapshotTask(taskId: String): SnapshotCatchupTask = {
    copy(snapshotTaskId = Some(taskId))
  }
  
  def withCatchupTask(taskId: String): SnapshotCatchupTask = {
    copy(catchupTaskId = Some(taskId))
  }
  
  def withLowWatermark(position: BinlogPosition): SnapshotCatchupTask = {
    copy(lowWatermark = Some(position))
  }
  
  def withTargetPosition(position: BinlogPosition): SnapshotCatchupTask = {
    copy(targetPosition = Some(position))
  }
  
  def withSnapshotResult(rows: Long): SnapshotCatchupTask = {
    copy(snapshotRows = rows)
  }
  
  def withCatchupResult(events: Long): SnapshotCatchupTask = {
    copy(catchupEvents = events)
  }
  
  def withError(error: String): SnapshotCatchupTask = {
    copy(status = SnapshotCatchupStatus.Failed, errorMessage = Some(error), completedAt = Some(Instant.now()))
  }
}

/**
 * 快照-Catchup 协调器
 * 负责协调快照和 Catchup 的完整流程
 */
class SnapshotCatchupCoordinator(
  catalogService: CatalogService,
  snapshotManager: SnapshotManager,
  catchupManager: CatchupManager,
  lowWatermarkManager: LowWatermarkManager,
  binlogReader: BinlogReader
)(implicit system: ActorSystem[_]) extends LazyLogging {
  
  implicit val ec: ExecutionContext = system.executionContext
  
  // 任务状态存储
  private val tasks = scala.collection.concurrent.TrieMap[String, SnapshotCatchupTask]()
  
  /**
   * 启动完整的快照-Catchup 流程
   */
  def startSnapshotCatchup(tableId: TableId): Future[String] = {
    logger.info(s"Starting snapshot-catchup process for table $tableId")
    
    val taskId = generateTaskId()
    val task = SnapshotCatchupTask(id = taskId, tableId = tableId)
    tasks += taskId -> task
    
    executeSnapshotCatchupFlow(task).recover { error =>
      logger.error(s"Snapshot-catchup failed for table $tableId: ${error.getMessage}", error)
      val failedTask = task.withError(error.getMessage)
      tasks += taskId -> failedTask
      taskId
    }
  }
  
  /**
   * 执行快照-Catchup 流程
   */
  private def executeSnapshotCatchupFlow(task: SnapshotCatchupTask): Future[String] = {
    for {
      // 步骤1：创建 Low Watermark
      lowWatermark <- createLowWatermark(task)
      
      // 步骤2：执行快照
      snapshotResult <- executeSnapshot(task.withLowWatermark(lowWatermark))
      
      // 步骤3：获取当前位置作为目标位置
      targetPosition <- getCurrentBinlogPosition()
      
      // 步骤4：执行 Catchup
      catchupResult <- executeCatchup(task.withTargetPosition(targetPosition), snapshotResult)
      
      // 步骤5：完成流程
      _ <- completeSnapshotCatchup(task, snapshotResult, catchupResult)
      
    } yield {
      logger.info(s"Snapshot-catchup completed for table ${task.tableId}")
      task.id
    }
  }
  
  /**
   * 创建 Low Watermark
   */
  private def createLowWatermark(task: SnapshotCatchupTask): Future[BinlogPosition] = {
    logger.info(s"Creating low watermark for table ${task.tableId}")
    
    lowWatermarkManager.createLowWatermark(task.tableId, task.id).map { watermark =>
      val updatedTask = task.withLowWatermark(watermark.position)
      tasks += task.id -> updatedTask
      
      logger.info(s"Low watermark created at position ${watermark.position}")
      watermark.position
    }
  }
  
  /**
   * 执行快照
   */
  private def executeSnapshot(task: SnapshotCatchupTask): Future[SnapshotResult] = {
    logger.info(s"Starting snapshot for table ${task.tableId}")
    
    val updatedTask = task.withStatus(SnapshotCatchupStatus.SnapshotInProgress)
    tasks += task.id -> updatedTask
    
    task.lowWatermark match {
      case Some(lowWatermark) =>
        for {
          snapshotTaskId <- snapshotManager.startSnapshot(task.tableId, lowWatermark)
          _ = {
            val taskWithSnapshot = updatedTask.withSnapshotTask(snapshotTaskId)
            tasks += task.id -> taskWithSnapshot
          }
          snapshotTask <- snapshotManager.waitForSnapshotCompletion(snapshotTaskId)
        } yield {
          logger.info(s"Snapshot completed for table ${task.tableId}, rows: ${snapshotTask.rowCount}")
          
          SnapshotResult(
            taskId = snapshotTaskId,
            tableId = task.tableId,
            rowCount = snapshotTask.rowCount,
            startTime = snapshotTask.startedAt.getOrElse(snapshotTask.createdAt),
            endTime = snapshotTask.completedAt.getOrElse(Instant.now()),
            chunks = 1
          )
        }
        
      case None =>
        Future.failed(new RuntimeException("Low watermark not available"))
    }
  }
  
  /**
   * 获取当前 Binlog 位置
   */
  private def getCurrentBinlogPosition(): Future[BinlogPosition] = {
    Future {
      val currentPosition = binlogReader.getCurrentPosition()
      logger.info(s"Current binlog position: $currentPosition")
      currentPosition
    }
  }
  
  /**
   * 执行 Catchup
   */
  private def executeCatchup(task: SnapshotCatchupTask, snapshotResult: SnapshotResult): Future[CatchupResult] = {
    logger.info(s"Starting catchup for table ${task.tableId}")
    
    val updatedTask = task.withStatus(SnapshotCatchupStatus.CatchupInProgress)
      .withSnapshotResult(snapshotResult.rowCount)
    tasks += task.id -> updatedTask
    
    task.targetPosition match {
      case Some(targetPosition) =>
        for {
          catchupTaskId <- catchupManager.startCatchup(task.tableId, task.id, targetPosition)
          _ = {
            val taskWithCatchup = updatedTask.withCatchupTask(catchupTaskId)
            tasks += task.id -> taskWithCatchup
          }
          // 等待 Catchup 完成（简化实现）
          _ <- waitForCatchupCompletion(catchupTaskId)
        } yield {
          logger.info(s"Catchup completed for table ${task.tableId}")
          
          CatchupResult(
            taskId = catchupTaskId,
            tableId = task.tableId,
            processedEvents = 0, // 简化实现
            startPosition = task.lowWatermark.get,
            endPosition = targetPosition,
            duration = 0
          )
        }
        
      case None =>
        Future.failed(new RuntimeException("Target position not available"))
    }
  }
  
  /**
   * 等待 Catchup 完成
   */
  private def waitForCatchupCompletion(catchupTaskId: String): Future[Unit] = {
    def poll(): Future[Unit] = {
      catchupManager.getCatchupProgress(catchupTaskId).flatMap { catchupTask =>
        if (catchupTask.isCompleted) {
          Future.successful(())
        } else if (catchupTask.isFailed) {
          Future.failed(new RuntimeException(s"Catchup failed: ${catchupTask.errorMessage.getOrElse("Unknown error")}"))
        } else {
          // 继续轮询
          Future {
            Thread.sleep(1000) // 1秒轮询间隔
          }.flatMap(_ => poll())
        }
      }
    }
    
    poll()
  }
  
  /**
   * 完成快照-Catchup 流程
   */
  private def completeSnapshotCatchup(
    task: SnapshotCatchupTask,
    snapshotResult: SnapshotResult,
    catchupResult: CatchupResult
  ): Future[Unit] = {
    
    logger.info(s"Completing snapshot-catchup for table ${task.tableId}")
    
    for {
      // 更新 Low Watermark 状态为已完成
      _ <- lowWatermarkManager.completeLowWatermark(task.tableId, task.id)
      
      // 更新任务状态
      _ = {
        val completedTask = task
          .withStatus(SnapshotCatchupStatus.Completed)
          .withSnapshotResult(snapshotResult.rowCount)
          .withCatchupResult(catchupResult.processedEvents)
        
        tasks += task.id -> completedTask
        
        logger.info(s"Snapshot-catchup completed for table ${task.tableId}: " +
          s"${snapshotResult.rowCount} snapshot rows, ${catchupResult.processedEvents} catchup events")
      }
    } yield ()
  }
  
  /**
   * 获取任务状态
   */
  def getTaskStatus(taskId: String): Option[SnapshotCatchupTask] = {
    tasks.get(taskId)
  }
  
  /**
   * 列出所有任务
   */
  def listTasks(tableId: Option[TableId] = None): Seq[SnapshotCatchupTask] = {
    val allTasks = tasks.values.toSeq
    tableId match {
      case Some(table) => allTasks.filter(_.tableId == table)
      case None => allTasks
    }
  }
  
  /**
   * 取消任务
   */
  def cancelTask(taskId: String): Future[Unit] = {
    tasks.get(taskId) match {
      case Some(task) if task.isRunning =>
        logger.info(s"Cancelling snapshot-catchup task $taskId")
        
        val futures = Seq(
          task.snapshotTaskId.map(snapshotManager.cancelSnapshot).getOrElse(Future.successful(())),
          task.catchupTaskId.map(catchupManager.cancelCatchup).getOrElse(Future.successful(()))
        )
        
        Future.sequence(futures).map { _ =>
          val cancelledTask = task.withStatus(SnapshotCatchupStatus.Cancelled)
          tasks += taskId -> cancelledTask
        }
        
      case Some(_) =>
        Future.failed(new RuntimeException(s"Task $taskId cannot be cancelled in current state"))
        
      case None =>
        Future.failed(new RuntimeException(s"Task $taskId not found"))
    }
  }
  
  /**
   * 获取统计信息
   */
  def getStatistics(): SnapshotCatchupStatistics = {
    val allTasks = tasks.values.toSeq
    val totalTasks = allTasks.size
    val completedTasks = allTasks.count(_.isCompleted)
    val failedTasks = allTasks.count(_.isFailed)
    val runningTasks = allTasks.count(_.isRunning)
    
    val totalSnapshotRows = allTasks.filter(_.isCompleted).map(_.snapshotRows).sum
    val totalCatchupEvents = allTasks.filter(_.isCompleted).map(_.catchupEvents).sum
    
    val completedTasksWithDuration = allTasks.filter(task => 
      task.isCompleted && task.startedAt.isDefined && task.completedAt.isDefined
    )
    
    val avgDuration = if (completedTasksWithDuration.nonEmpty) {
      val totalDuration = completedTasksWithDuration.map { task =>
        task.completedAt.get.toEpochMilli - task.startedAt.get.toEpochMilli
      }.sum
      totalDuration / completedTasksWithDuration.size
    } else 0L
    
    SnapshotCatchupStatistics(
      totalTasks = totalTasks,
      completedTasks = completedTasks,
      failedTasks = failedTasks,
      runningTasks = runningTasks,
      totalSnapshotRows = totalSnapshotRows,
      totalCatchupEvents = totalCatchupEvents,
      avgDurationMs = avgDuration
    )
  }
  
  /**
   * 清理已完成的任务
   */
  def cleanupCompletedTasks(olderThan: FiniteDuration = 24.hours): Int = {
    val cutoffTime = Instant.now().minusMillis(olderThan.toMillis)
    
    val tasksToRemove = tasks.filter { case (_, task) =>
      (task.isCompleted || task.isFailed) && 
      task.completedAt.exists(_.isBefore(cutoffTime))
    }.keys.toSeq
    
    tasksToRemove.foreach(tasks.remove)
    
    logger.info(s"Cleaned up ${tasksToRemove.size} completed snapshot-catchup tasks")
    tasksToRemove.size
  }
  
  private def generateTaskId(): String = {
    s"snapshot-catchup-${System.currentTimeMillis()}-${scala.util.Random.nextInt(1000)}"
  }
}

/**
 * 快照-Catchup 统计信息
 */
case class SnapshotCatchupStatistics(
  totalTasks: Int,
  completedTasks: Int,
  failedTasks: Int,
  runningTasks: Int,
  totalSnapshotRows: Long,
  totalCatchupEvents: Long,
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
    "totalSnapshotRows" -> totalSnapshotRows,
    "totalCatchupEvents" -> totalCatchupEvents,
    "avgDurationMs" -> avgDurationMs,
    "successRate" -> successRate
  )
}

object SnapshotCatchupCoordinator {
  /**
   * 创建快照-Catchup 协调器
   */
  def apply(
    catalogService: CatalogService,
    snapshotManager: SnapshotManager,
    catchupManager: CatchupManager,
    lowWatermarkManager: LowWatermarkManager,
    binlogReader: BinlogReader
  )(implicit system: ActorSystem[_]): SnapshotCatchupCoordinator = {
    new SnapshotCatchupCoordinator(
      catalogService,
      snapshotManager,
      catchupManager,
      lowWatermarkManager,
      binlogReader
    )
  }
}