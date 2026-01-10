package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * 快照任务状态
 */
sealed trait SnapshotTaskStatus
case object Pending extends SnapshotTaskStatus
case object Running extends SnapshotTaskStatus
case object Completed extends SnapshotTaskStatus
case object Failed extends SnapshotTaskStatus
case object Cancelled extends SnapshotTaskStatus

/**
 * 快照任务
 */
case class SnapshotTask(
  id: String,
  tableId: TableId,
  lowWatermark: BinlogPosition,
  startKey: Option[Any] = None,
  endKey: Option[Any] = None,
  status: SnapshotTaskStatus = Pending,
  createdAt: Instant = Instant.now(),
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  rowCount: Long = 0,
  errorMessage: Option[String] = None
) {
  def isCompleted: Boolean = status == Completed
  def isFailed: Boolean = status == Failed
  def isRunning: Boolean = status == Running
  def isPending: Boolean = status == Pending
  
  def withStatus(newStatus: SnapshotTaskStatus): SnapshotTask = {
    val now = Instant.now()
    newStatus match {
      case Running => copy(status = newStatus, startedAt = Some(now))
      case Completed | Failed | Cancelled => copy(status = newStatus, completedAt = Some(now))
      case _ => copy(status = newStatus)
    }
  }
  
  def withError(error: String): SnapshotTask = {
    copy(status = Failed, errorMessage = Some(error), completedAt = Some(Instant.now()))
  }
  
  def withRowCount(count: Long): SnapshotTask = {
    copy(rowCount = count)
  }
}

/**
 * 快照调度器消息
 */
sealed trait SnapshotSchedulerMessage

case class ScheduleSnapshot(
  tableId: TableId,
  lowWatermark: BinlogPosition,
  replyTo: ActorRef[SnapshotSchedulerResponse]
) extends SnapshotSchedulerMessage

case class GetSnapshotStatus(
  taskId: String,
  replyTo: ActorRef[SnapshotSchedulerResponse]
) extends SnapshotSchedulerMessage

case class ListSnapshotTasks(
  tableId: Option[TableId],
  replyTo: ActorRef[SnapshotSchedulerResponse]
) extends SnapshotSchedulerMessage

case class CancelSnapshot(
  taskId: String,
  replyTo: ActorRef[SnapshotSchedulerResponse]
) extends SnapshotSchedulerMessage

case object GetSchedulerStats extends SnapshotSchedulerMessage

private case class TaskCompleted(taskId: String, result: SnapshotResult) extends SnapshotSchedulerMessage
private case class TaskFailed(taskId: String, error: Throwable) extends SnapshotSchedulerMessage
private case object ProcessPendingTasks extends SnapshotSchedulerMessage

/**
 * 快照调度器响应
 */
sealed trait SnapshotSchedulerResponse

case class SnapshotScheduled(taskId: String) extends SnapshotSchedulerResponse
case class SnapshotTaskStatusResponse(task: SnapshotTask) extends SnapshotSchedulerResponse
case class SnapshotTaskList(tasks: Seq[SnapshotTask]) extends SnapshotSchedulerResponse
case class SnapshotCancelled(taskId: String) extends SnapshotSchedulerResponse
case class SchedulerStats(
  totalTasks: Int,
  pendingTasks: Int,
  runningTasks: Int,
  completedTasks: Int,
  failedTasks: Int
) extends SnapshotSchedulerResponse
case class SnapshotError(message: String) extends SnapshotSchedulerResponse

/**
 * 快照调度器配置
 */
case class SnapshotSchedulerConfig(
  maxConcurrentTasks: Int = 4,
  taskTimeoutMinutes: Int = 60,
  maxRetries: Int = 3,
  retryDelaySeconds: Int = 30,
  processingIntervalSeconds: Int = 5,
  maxRowsPerChunk: Long = 10000
)

/**
 * 快照调度器
 * 负责管理和调度表快照任务
 */
object SnapshotScheduler extends LazyLogging {
  
  private val taskIdGenerator = new AtomicLong(0)
  
  def apply(
    config: SnapshotSchedulerConfig,
    workerPool: ActorRef[SnapshotWorkerMessage]
  ): Behavior[SnapshotSchedulerMessage] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        implicit val ec: ExecutionContext = context.executionContext
        
        // 定期处理待处理任务
        timers.startTimerWithFixedDelay(
          ProcessPendingTasks,
          config.processingIntervalSeconds.seconds
        )
        
        running(config, workerPool, Map.empty, Map.empty)
      }
    }
  }
  
  private def running(
    config: SnapshotSchedulerConfig,
    workerPool: ActorRef[SnapshotWorkerMessage],
    tasks: Map[String, SnapshotTask],
    runningTasks: Map[String, ActorRef[SnapshotWorkerMessage]]
  ): Behavior[SnapshotSchedulerMessage] = {
    
    Behaviors.receive { (context, message) =>
      message match {
        case ScheduleSnapshot(tableId, lowWatermark, replyTo) =>
          val taskId = generateTaskId()
          val task = SnapshotTask(
            id = taskId,
            tableId = tableId,
            lowWatermark = lowWatermark
          )
          
          logger.info(s"Scheduling snapshot task $taskId for table $tableId")
          replyTo ! SnapshotScheduled(taskId)
          
          val updatedTasks = tasks + (taskId -> task)
          running(config, workerPool, updatedTasks, runningTasks)
          
        case GetSnapshotStatus(taskId, replyTo) =>
          tasks.get(taskId) match {
            case Some(task) =>
              replyTo ! SnapshotTaskStatusResponse(task)
            case None =>
              replyTo ! SnapshotError(s"Task $taskId not found")
          }
          Behaviors.same
          
        case ListSnapshotTasks(tableIdFilter, replyTo) =>
          val filteredTasks = tableIdFilter match {
            case Some(tableId) => tasks.values.filter(_.tableId == tableId).toSeq
            case None => tasks.values.toSeq
          }
          replyTo ! SnapshotTaskList(filteredTasks)
          Behaviors.same
          
        case CancelSnapshot(taskId, replyTo) =>
          tasks.get(taskId) match {
            case Some(task) if task.isPending =>
              val cancelledTask = task.withStatus(Cancelled)
              replyTo ! SnapshotCancelled(taskId)
              running(config, workerPool, tasks + (taskId -> cancelledTask), runningTasks)
              
            case Some(task) if task.isRunning =>
              // 通知工作器取消任务
              runningTasks.get(taskId).foreach { workerRef =>
                workerRef ! CancelSnapshotTask(taskId)
              }
              replyTo ! SnapshotCancelled(taskId)
              Behaviors.same
              
            case Some(_) =>
              replyTo ! SnapshotError(s"Task $taskId cannot be cancelled in current state")
              Behaviors.same
              
            case None =>
              replyTo ! SnapshotError(s"Task $taskId not found")
              Behaviors.same
          }
          
        case GetSchedulerStats =>
          val stats = calculateStats(tasks.values.toSeq)
          context.self ! stats.asInstanceOf[SnapshotSchedulerMessage]
          Behaviors.same
          
        case ProcessPendingTasks =>
          val pendingTasks = tasks.values.filter(_.isPending).toSeq
          val currentRunning = runningTasks.size
          val availableSlots = config.maxConcurrentTasks - currentRunning
          
          if (availableSlots > 0 && pendingTasks.nonEmpty) {
            val tasksToStart = pendingTasks.take(availableSlots)
            
            val (updatedTasks, updatedRunning) = tasksToStart.foldLeft((tasks, runningTasks)) {
              case ((taskMap, runningMap), task) =>
                logger.info(s"Starting snapshot task ${task.id} for table ${task.tableId}")
                
                val runningTask = task.withStatus(Running)
                val workerRef = context.spawn(
                  SnapshotWorker(task, config),
                  s"snapshot-worker-${task.id}"
                )
                
                // 启动快照任务
                workerRef ! StartSnapshot(
                  task,
                  context.messageAdapter[SnapshotResult](result => TaskCompleted(task.id, result)),
                  context.messageAdapter[Throwable](error => TaskFailed(task.id, error))
                )
                
                (taskMap + (task.id -> runningTask), runningMap + (task.id -> workerRef))
            }
            
            running(config, workerPool, updatedTasks, updatedRunning)
          } else {
            Behaviors.same
          }
          
        case TaskCompleted(taskId, result) =>
          tasks.get(taskId) match {
            case Some(task) =>
              logger.info(s"Snapshot task $taskId completed with ${result.rowCount} rows")
              val completedTask = task.withStatus(Completed).withRowCount(result.rowCount)
              val updatedTasks = tasks + (taskId -> completedTask)
              val updatedRunning = runningTasks - taskId
              
              running(config, workerPool, updatedTasks, updatedRunning)
              
            case None =>
              logger.warn(s"Received completion for unknown task $taskId")
              Behaviors.same
          }
          
        case TaskFailed(taskId, error) =>
          tasks.get(taskId) match {
            case Some(task) =>
              logger.error(s"Snapshot task $taskId failed: ${error.getMessage}", error)
              val failedTask = task.withError(error.getMessage)
              val updatedTasks = tasks + (taskId -> failedTask)
              val updatedRunning = runningTasks - taskId
              
              running(config, workerPool, updatedTasks, updatedRunning)
              
            case None =>
              logger.warn(s"Received failure for unknown task $taskId")
              Behaviors.same
          }
      }
    }
  }
  
  private def generateTaskId(): String = {
    s"snapshot-${System.currentTimeMillis()}-${taskIdGenerator.incrementAndGet()}"
  }
  
  private def calculateStats(tasks: Seq[SnapshotTask]): SchedulerStats = {
    val total = tasks.size
    val pending = tasks.count(_.isPending)
    val running = tasks.count(_.isRunning)
    val completed = tasks.count(_.isCompleted)
    val failed = tasks.count(_.isFailed)
    
    SchedulerStats(total, pending, running, completed, failed)
  }
}