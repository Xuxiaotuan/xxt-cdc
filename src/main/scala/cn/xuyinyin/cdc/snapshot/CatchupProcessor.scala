package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent, Delete, Insert, TableId, Update}
import cn.xuyinyin.cdc.normalizer.EventNormalizer
import cn.xuyinyin.cdc.reader.{BinlogReader, RawBinlogEvent}
import cn.xuyinyin.cdc.sink.MySQLSink
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.stream.{KillSwitches, SharedKillSwitch}

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * Catchup 状态
 */
sealed trait CatchupStatus
object CatchupStatus {
  case object NotStarted extends CatchupStatus
  case object Running extends CatchupStatus
  case object Completed extends CatchupStatus
  case object Failed extends CatchupStatus
  case object Cancelled extends CatchupStatus
}

/**
 * Catchup 任务
 */
case class CatchupTask(
  id: String,
  tableId: TableId,
  lowWatermark: BinlogPosition,
  targetPosition: BinlogPosition,
  snapshotId: String,
  status: CatchupStatus = CatchupStatus.NotStarted,
  createdAt: Instant = Instant.now(),
  startedAt: Option[Instant] = None,
  completedAt: Option[Instant] = None,
  processedEvents: Long = 0,
  lastProcessedPosition: Option[BinlogPosition] = None,
  errorMessage: Option[String] = None
) {
  def isRunning: Boolean = status == CatchupStatus.Running
  def isCompleted: Boolean = status == CatchupStatus.Completed
  def isFailed: Boolean = status == CatchupStatus.Failed
  def isCancelled: Boolean = status == CatchupStatus.Cancelled
  
  def withStatus(newStatus: CatchupStatus): CatchupTask = {
    val now = Instant.now()
    newStatus match {
      case CatchupStatus.Running => copy(status = newStatus, startedAt = Some(now))
      case CatchupStatus.Completed | CatchupStatus.Failed | CatchupStatus.Cancelled => 
        copy(status = newStatus, completedAt = Some(now))
      case _ => copy(status = newStatus)
    }
  }
  
  def withProgress(events: Long, position: BinlogPosition): CatchupTask = {
    copy(processedEvents = events, lastProcessedPosition = Some(position))
  }
  
  def withError(error: String): CatchupTask = {
    copy(status = CatchupStatus.Failed, errorMessage = Some(error), completedAt = Some(Instant.now()))
  }
}

/**
 * Catchup 结果
 */
case class CatchupResult(
  taskId: String,
  tableId: TableId,
  processedEvents: Long,
  startPosition: BinlogPosition,
  endPosition: BinlogPosition,
  duration: Long
)

/**
 * Catchup 处理器消息
 */
sealed trait CatchupProcessorMessage

case class StartCatchup(
  task: CatchupTask,
  onComplete: ActorRef[CatchupResult],
  onError: ActorRef[Throwable]
) extends CatchupProcessorMessage

case class CancelCatchup(taskId: String) extends CatchupProcessorMessage

case class GetCatchupProgress(
  taskId: String,
  replyTo: ActorRef[CatchupTask]
) extends CatchupProcessorMessage

private case class UpdateProgress(events: Long, position: BinlogPosition) extends CatchupProcessorMessage
private case class CatchupCompleted(result: CatchupResult) extends CatchupProcessorMessage
private case class CatchupFailed(error: Throwable) extends CatchupProcessorMessage

/**
 * Catchup 处理器
 * 负责从 Low Watermark 开始消费 binlog，追平到快照完成时刻的变更
 */
object CatchupProcessor extends LazyLogging {
  
  def apply(
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    sink: MySQLSink
  )(implicit system: ActorSystem[_]): Behavior[CatchupProcessorMessage] = {
    Behaviors.setup { context =>
      implicit val ec: ExecutionContext = context.executionContext
      
      idle(binlogReader, eventNormalizer, sink, None, None)
    }
  }
  
  private def idle(
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    sink: MySQLSink,
    currentTask: Option[CatchupTask],
    killSwitch: Option[SharedKillSwitch]
  )(implicit system: ActorSystem[_]): Behavior[CatchupProcessorMessage] = {
    
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext
      
      message match {
        case StartCatchup(task, onComplete, onError) =>
          if (currentTask.isDefined) {
            onError ! new RuntimeException("Catchup processor is already running")
            Behaviors.same
          } else {
            logger.info(s"Starting catchup for table ${task.tableId} from ${task.lowWatermark} to ${task.targetPosition}")
            
            val runningTask = task.withStatus(CatchupStatus.Running)
            val newKillSwitch = KillSwitches.shared("catchup-" + task.id)
            
            // 启动 catchup 流处理
            val catchupFuture = startCatchupStream(
              task,
              binlogReader,
              eventNormalizer,
              sink,
              newKillSwitch,
              context.self
            )
            
            catchupFuture.onComplete {
              case Success(result) =>
                context.self ! CatchupCompleted(result)
                onComplete ! result
              case Failure(error) =>
                context.self ! CatchupFailed(error)
                onError ! error
            }
            
            running(binlogReader, eventNormalizer, sink, Some(runningTask), Some(newKillSwitch))
          }
          
        case GetCatchupProgress(taskId, replyTo) =>
          currentTask match {
            case Some(task) if task.id == taskId =>
              replyTo ! task
            case _ =>
              replyTo ! CatchupTask("", TableId("", ""), cn.xuyinyin.cdc.model.FilePosition("", 0), 
                cn.xuyinyin.cdc.model.FilePosition("", 0), "", CatchupStatus.NotStarted)
          }
          Behaviors.same
          
        case _ =>
          Behaviors.unhandled
      }
    }
  }
  
  private def running(
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    sink: MySQLSink,
    currentTask: Option[CatchupTask],
    killSwitch: Option[SharedKillSwitch]
  )(implicit system: ActorSystem[_]): Behavior[CatchupProcessorMessage] = {
    
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext
      
      message match {
        case CancelCatchup(taskId) =>
          currentTask match {
            case Some(task) if task.id == taskId =>
              logger.info(s"Cancelling catchup task $taskId")
              killSwitch.foreach(_.shutdown())
              
              val cancelledTask = task.withStatus(CatchupStatus.Cancelled)
              idle(binlogReader, eventNormalizer, sink, None, None)
              
            case _ =>
              logger.warn(s"Cannot cancel catchup task $taskId - not found or not running")
              Behaviors.same
          }
          
        case UpdateProgress(events, position) =>
          currentTask match {
            case Some(task) =>
              val updatedTask = task.withProgress(events, position)
              running(binlogReader, eventNormalizer, sink, Some(updatedTask), killSwitch)
            case None =>
              Behaviors.same
          }
          
        case CatchupCompleted(result) =>
          logger.info(s"Catchup completed for task ${result.taskId}, processed ${result.processedEvents} events")
          idle(binlogReader, eventNormalizer, sink, None, None)
          
        case CatchupFailed(error) =>
          logger.error(s"Catchup failed: ${error.getMessage}", error)
          idle(binlogReader, eventNormalizer, sink, None, None)
          
        case GetCatchupProgress(taskId, replyTo) =>
          currentTask match {
            case Some(task) if task.id == taskId =>
              replyTo ! task
            case _ =>
              replyTo ! CatchupTask("", TableId("", ""), cn.xuyinyin.cdc.model.FilePosition("", 0), 
                cn.xuyinyin.cdc.model.FilePosition("", 0), "", CatchupStatus.NotStarted)
          }
          Behaviors.same
          
        case _ =>
          Behaviors.unhandled
      }
    }
  }
  
  /**
   * 启动 Catchup 流处理
   */
  private def startCatchupStream(
    task: CatchupTask,
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    sink: MySQLSink,
    killSwitch: SharedKillSwitch,
    progressActor: ActorRef[CatchupProcessorMessage]
  )(implicit system: ActorSystem[_]): Future[CatchupResult] = {
    
    implicit val ec: ExecutionContext = system.executionContext
    val startTime = Instant.now()
    
    logger.info(s"Starting catchup stream for table ${task.tableId}")
    
    // 创建 binlog 事件源，从 low watermark 开始
    val binlogSource = createBinlogSource(binlogReader, task.lowWatermark, task.targetPosition)
    
    // 事件过滤和标准化流
    val eventProcessingFlow = createEventProcessingFlow(
      task.tableId,
      eventNormalizer,
      task.targetPosition,
      progressActor
    )
    
    // 创建写入 sink
    val writeSink = createWriteSink(sink)
    
    // 构建完整的流处理管道
    val resultPromise = Promise[CatchupResult]()
    var processedEvents = 0L
    
    binlogSource
      .via(killSwitch.flow)
      .via(eventProcessingFlow)
      .alsoTo(Flow[ChangeEvent].map { _ =>
        processedEvents += 1
        if (processedEvents % 1000 == 0) {
          logger.debug(s"Catchup processed $processedEvents events for table ${task.tableId}")
        }
      }.to(Sink.ignore))
      .runWith(writeSink)
      .onComplete {
        case Success(_) =>
          val endTime = Instant.now()
          val result = CatchupResult(
            taskId = task.id,
            tableId = task.tableId,
            processedEvents = processedEvents,
            startPosition = task.lowWatermark,
            endPosition = task.targetPosition,
            duration = endTime.toEpochMilli - startTime.toEpochMilli
          )
          resultPromise.success(result)
          
        case Failure(error) =>
          logger.error(s"Catchup stream failed for table ${task.tableId}: ${error.getMessage}", error)
          resultPromise.failure(error)
      }
    
    resultPromise.future
  }
  
  /**
   * 创建 Binlog 事件源
   */
  private def createBinlogSource(
    binlogReader: BinlogReader,
    startPosition: BinlogPosition,
    endPosition: BinlogPosition
  )(implicit system: ActorSystem[_]): Source[RawBinlogEvent, NotUsed] = {
    
    // 使用 binlog reader 从指定位置开始读取
    binlogReader.start(startPosition)
      .takeWhile { event =>
        // 继续读取直到达到结束位置
        !isPositionReached(event.position, endPosition)
      }
  }
  
  /**
   * 创建事件处理流
   */
  private def createEventProcessingFlow(
    targetTableId: TableId,
    eventNormalizer: EventNormalizer,
    targetPosition: BinlogPosition,
    progressActor: ActorRef[CatchupProcessorMessage]
  ): Flow[RawBinlogEvent, ChangeEvent, NotUsed] = {
    
    Flow[RawBinlogEvent]
      .mapConcat { rawEvent =>
        // 标准化事件
        eventNormalizer.normalize(rawEvent).toList
      }
      .filter { changeEvent =>
        // 只处理目标表的事件
        changeEvent.tableId == targetTableId
      }
      .map { changeEvent =>
        // 更新进度
        progressActor ! UpdateProgress(1, changeEvent.position)
        changeEvent
      }
  }
  
  /**
   * 创建写入 Sink
   */
  private def createWriteSink(sink: MySQLSink)(implicit ec: ExecutionContext): Sink[ChangeEvent, Future[Done]] = {
    Flow[ChangeEvent]
      .grouped(100) // 批量处理
      .mapAsync(1) { batch =>
        // 对每个事件执行相应的操作
        Future.sequence(batch.map { event =>
          event.operation match {
            case Insert =>
              sink.executeInsert(event.tableId, event.after.getOrElse(Map.empty))
            case Update =>
              val pk = event.primaryKey
              val data = event.after.getOrElse(Map.empty)
              sink.executeUpdate(event.tableId, pk, data)
            case Delete =>
              val pk = event.primaryKey
              sink.executeDelete(event.tableId, pk)
          }
        }).map(_ => Done)
      }
      .toMat(Sink.ignore)((_, _) => Future.successful(Done))
  }
  
  /**
   * 检查是否已经到达目标位置
   */
  private def isPositionReached(current: BinlogPosition, target: BinlogPosition): Boolean = {
    // 简化的位置比较，实际实现需要根据具体的 BinlogPosition 类型来实现
    current.toString >= target.toString
  }
  
  /**
   * 从事件中提取位置信息
   */
  private def extractPositionFromEvent(event: Any): BinlogPosition = {
    // 简化实现，实际需要根据事件类型提取位置
    cn.xuyinyin.cdc.model.FilePosition("", 0)
  }
}

/**
 * Catchup 管理器
 * 负责管理多个表的 Catchup 任务
 */
class CatchupManager(
  binlogReader: BinlogReader,
  eventNormalizer: EventNormalizer,
  sink: MySQLSink,
  lowWatermarkManager: LowWatermarkManager
)(implicit system: ActorSystem[_]) extends LazyLogging {
  
  implicit val ec: ExecutionContext = system.executionContext
  private val catchupProcessors = scala.collection.mutable.Map[String, ActorRef[CatchupProcessorMessage]]()
  
  /**
   * 启动 Catchup 处理
   */
  def startCatchup(
    tableId: TableId,
    snapshotId: String,
    targetPosition: BinlogPosition
  ): Future[String] = {
    
    logger.info(s"Starting catchup for table $tableId, snapshot $snapshotId")
    
    // 获取 Low Watermark
    lowWatermarkManager.getLowWatermarkBySnapshot(snapshotId).flatMap {
      case Some(watermark) =>
        val taskId = generateCatchupTaskId()
        val task = CatchupTask(
          id = taskId,
          tableId = tableId,
          lowWatermark = watermark.position,
          targetPosition = targetPosition,
          snapshotId = snapshotId
        )
        
        // 创建 Catchup 处理器
        val processor = system.systemActorOf(
          CatchupProcessor(binlogReader, eventNormalizer, sink),
          s"catchup-processor-$taskId"
        )
        
        catchupProcessors += taskId -> processor
        
        // 启动 Catchup
        val resultPromise = Promise[CatchupResult]()
        val errorPromise = Promise[Throwable]()
        
        processor ! StartCatchup(
          task,
          system.ignoreRef, // 简化处理
          system.ignoreRef  // 简化处理
        )
        
        Future.successful(taskId)
        
      case None =>
        Future.failed(new RuntimeException(s"Low watermark not found for snapshot $snapshotId"))
    }
  }
  
  /**
   * 获取 Catchup 进度
   */
  def getCatchupProgress(taskId: String): Future[CatchupTask] = {
    catchupProcessors.get(taskId) match {
      case Some(processor) =>
        import org.apache.pekko.actor.typed.scaladsl.AskPattern._
        import org.apache.pekko.util.Timeout
        implicit val timeout: Timeout = 10.seconds
        
        processor.ask[CatchupTask](ref => GetCatchupProgress(taskId, ref))
        
      case None =>
        Future.failed(new RuntimeException(s"Catchup task $taskId not found"))
    }
  }
  
  /**
   * 取消 Catchup
   */
  def cancelCatchup(taskId: String): Future[Unit] = {
    catchupProcessors.get(taskId) match {
      case Some(processor) =>
        processor ! CancelCatchup(taskId)
        catchupProcessors -= taskId
        Future.successful(())
        
      case None =>
        Future.failed(new RuntimeException(s"Catchup task $taskId not found"))
    }
  }
  
  /**
   * 列出所有 Catchup 任务
   */
  def listCatchupTasks(): Future[Seq[String]] = {
    Future.successful(catchupProcessors.keys.toSeq)
  }
  
  private def generateCatchupTaskId(): String = {
    s"catchup-${System.currentTimeMillis()}-${scala.util.Random.nextInt(1000)}"
  }
}

object CatchupManager {
  /**
   * 创建 Catchup 管理器
   */
  def apply(
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    sink: MySQLSink,
    lowWatermarkManager: LowWatermarkManager
  )(implicit system: ActorSystem[_]): CatchupManager = {
    new CatchupManager(binlogReader, eventNormalizer, sink, lowWatermarkManager)
  }
}