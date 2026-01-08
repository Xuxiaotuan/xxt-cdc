package cn.xuyinyin.cdc.ddl

import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * DDL 事件监听器消息
 */
sealed trait DDLEventListenerMessage

case class RegisterDDLListener(
  tableId: Option[TableId],
  listener: ActorRef[DDLNotification]
) extends DDLEventListenerMessage

case class UnregisterDDLListener(
  listener: ActorRef[DDLNotification]
) extends DDLEventListenerMessage

case class ProcessDDLEvent(
  ddlEvent: DDLEvent
) extends DDLEventListenerMessage

case class GetDDLListenerStats(
  replyTo: ActorRef[DDLListenerStats]
) extends DDLEventListenerMessage

private case object CleanupExpiredListeners extends DDLEventListenerMessage

/**
 * DDL 通知消息
 */
sealed trait DDLNotification

case class DDLDetected(
  ddlEvent: DDLEvent,
  handlingResult: DDLHandlingResult
) extends DDLNotification

case class DDLProcessingFailed(
  ddlEvent: DDLEvent,
  error: Throwable
) extends DDLNotification

/**
 * DDL 监听器统计信息
 */
case class DDLListenerStats(
  activeListeners: Int,
  processedEvents: Long,
  failedEvents: Long,
  lastEventTime: Option[Instant]
)

/**
 * DDL 监听器注册信息
 */
private case class DDLListenerRegistration(
  listener: ActorRef[DDLNotification],
  tableFilter: Option[TableId],
  registeredAt: Instant = Instant.now()
)

/**
 * DDL 事件监听器
 * 负责管理 DDL 事件的监听器注册和事件分发
 */
object DDLEventListener extends LazyLogging {
  
  def apply(
    ddlHandler: DDLHandler,
    alertManager: DDLAlertManager
  ): Behavior[DDLEventListenerMessage] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        implicit val ec: ExecutionContext = context.executionContext
        
        // 定期清理过期的监听器
        timers.startTimerWithFixedDelay(
          CleanupExpiredListeners,
          5.minutes
        )
        
        running(ddlHandler, alertManager, Set.empty, 0, 0, None)
      }
    }
  }
  
  private def running(
    ddlHandler: DDLHandler,
    alertManager: DDLAlertManager,
    listeners: Set[DDLListenerRegistration],
    processedEvents: Long,
    failedEvents: Long,
    lastEventTime: Option[Instant]
  ): Behavior[DDLEventListenerMessage] = {
    
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext
      
      message match {
        case RegisterDDLListener(tableId, listener) =>
          logger.info(s"Registering DDL listener for table filter: ${tableId.getOrElse("all tables")}")
          
          val registration = DDLListenerRegistration(listener, tableId)
          val updatedListeners = listeners + registration
          
          running(ddlHandler, alertManager, updatedListeners, processedEvents, failedEvents, lastEventTime)
          
        case UnregisterDDLListener(listener) =>
          logger.info("Unregistering DDL listener")
          
          val updatedListeners = listeners.filterNot(_.listener == listener)
          
          running(ddlHandler, alertManager, updatedListeners, processedEvents, failedEvents, lastEventTime)
          
        case ProcessDDLEvent(ddlEvent) =>
          logger.info(s"Processing DDL event: ${ddlEvent.eventType.name} on ${ddlEvent.table.getOrElse("unknown table")}")
          
          // 处理 DDL 事件
          val handlingResult = ddlHandler.handleDDLEvent(ddlEvent)
          
          // 发送告警（如果需要）
          handlingResult match {
            case DDLAlerted =>
              alertManager.sendDDLAlert(ddlEvent)
            case DDLFailed(reason) =>
              alertManager.sendDDLAlert(ddlEvent, Some(reason))
            case _ =>
              // 其他情况不需要特殊处理
          }
          
          // 通知所有匹配的监听器
          val matchingListeners = listeners.filter { registration =>
            registration.tableFilter match {
              case Some(tableFilter) => ddlEvent.table.contains(tableFilter)
              case None => true // 监听所有表
            }
          }
          
          matchingListeners.foreach { registration =>
            try {
              registration.listener ! DDLDetected(ddlEvent, handlingResult)
            } catch {
              case ex: Exception =>
                logger.warn(s"Failed to notify DDL listener: ${ex.getMessage}")
            }
          }
          
          val newProcessedEvents = processedEvents + 1
          val newLastEventTime = Some(Instant.now())
          
          running(ddlHandler, alertManager, listeners, newProcessedEvents, failedEvents, newLastEventTime)
          
        case GetDDLListenerStats(replyTo) =>
          val stats = DDLListenerStats(
            activeListeners = listeners.size,
            processedEvents = processedEvents,
            failedEvents = failedEvents,
            lastEventTime = lastEventTime
          )
          
          replyTo ! stats
          Behaviors.same
          
        case CleanupExpiredListeners =>
          // 清理可能已经停止的监听器
          // 在实际实现中，可以通过检查 ActorRef 的状态来判断
          val activeListeners = listeners.filter { registration =>
            // 简化实现，实际应该检查 ActorRef 是否还活跃
            true
          }
          
          if (activeListeners.size != listeners.size) {
            logger.info(s"Cleaned up ${listeners.size - activeListeners.size} expired DDL listeners")
            running(ddlHandler, alertManager, activeListeners, processedEvents, failedEvents, lastEventTime)
          } else {
            Behaviors.same
          }
      }
    }
  }
}

/**
 * DDL 事件处理器
 * 提供高级的 DDL 事件处理功能
 */
class DDLEventProcessor(
  ddlHandler: DDLHandler,
  alertManager: DDLAlertManager,
  listener: ActorRef[DDLEventListenerMessage]
)(implicit system: ActorSystem[_]) extends LazyLogging {
  
  implicit val ec: ExecutionContext = system.executionContext
  
  /**
   * 处理 DDL 事件
   */
  def processDDLEvent(ddlEvent: DDLEvent): Future[DDLHandlingResult] = {
    Future {
      logger.info(s"Processing DDL event: ${ddlEvent.eventType.name}")
      
      // 发送到监听器进行处理
      listener ! ProcessDDLEvent(ddlEvent)
      
      // 返回处理结果
      ddlHandler.handleDDLEvent(ddlEvent)
    }
  }
  
  /**
   * 注册 DDL 监听器
   */
  def registerListener(
    tableId: Option[TableId],
    listenerRef: ActorRef[DDLNotification]
  ): Unit = {
    listener ! RegisterDDLListener(tableId, listenerRef)
  }
  
  /**
   * 注销 DDL 监听器
   */
  def unregisterListener(listenerRef: ActorRef[DDLNotification]): Unit = {
    listener ! UnregisterDDLListener(listenerRef)
  }
  
  /**
   * 获取监听器统计信息
   */
  def getListenerStats(): Future[DDLListenerStats] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import org.apache.pekko.util.Timeout
    implicit val timeout: Timeout = 10.seconds
    
    listener.ask[DDLListenerStats](ref => GetDDLListenerStats(ref))
  }
  
  /**
   * 创建表级 DDL 监听器
   */
  def createTableDDLListener(tableId: TableId): ActorRef[DDLNotification] = {
    system.systemActorOf(
      Behaviors.receive[DDLNotification] { (context, message) =>
        message match {
          case DDLDetected(ddlEvent, result) =>
            logger.info(s"Table $tableId received DDL event: ${ddlEvent.eventType.name}")
            handleTableDDLEvent(tableId, ddlEvent, result)
            Behaviors.same
            
          case DDLProcessingFailed(ddlEvent, error) =>
            logger.error(s"Table $tableId DDL processing failed: ${error.getMessage}", error)
            handleTableDDLFailure(tableId, ddlEvent, error)
            Behaviors.same
        }
      },
      s"table-ddl-listener-${tableId.database}-${tableId.table}"
    )
  }
  
  /**
   * 处理表级 DDL 事件
   */
  private def handleTableDDLEvent(
    tableId: TableId,
    ddlEvent: DDLEvent,
    result: DDLHandlingResult
  ): Unit = {
    result match {
      case DDLFailed(reason) =>
        logger.error(s"DDL event failed for table $tableId: $reason")
        // 可以在这里实现表级的错误处理逻辑
        
      case DDLAlerted =>
        logger.warn(s"DDL alert for table $tableId: ${ddlEvent.eventType.name}")
        // 可以在这里实现表级的告警处理逻辑
        
      case DDLLogged =>
        logger.info(s"DDL event logged for table $tableId: ${ddlEvent.eventType.name}")
        
      case DDLIgnored =>
        logger.debug(s"DDL event ignored for table $tableId: ${ddlEvent.eventType.name}")
    }
  }
  
  /**
   * 处理表级 DDL 失败
   */
  private def handleTableDDLFailure(
    tableId: TableId,
    ddlEvent: DDLEvent,
    error: Throwable
  ): Unit = {
    logger.error(s"DDL processing failed for table $tableId: ${error.getMessage}", error)
    
    // 发送失败告警
    alertManager.sendDDLAlert(ddlEvent, Some(error.getMessage))
    
    // 可以在这里实现更复杂的失败处理逻辑
    // 例如：暂停该表的 CDC 处理、发送紧急告警等
  }
}

/**
 * DDL 事件处理工厂
 */
object DDLEventProcessor {
  
  /**
   * 创建 DDL 事件处理器
   */
  def apply(
    ddlHandler: DDLHandler,
    alertManager: DDLAlertManager
  )(implicit system: ActorSystem[_]): DDLEventProcessor = {
    
    val listener = system.systemActorOf(
      DDLEventListener(ddlHandler, alertManager),
      "ddl-event-listener"
    )
    
    new DDLEventProcessor(ddlHandler, alertManager, listener)
  }
  
  /**
   * 创建带配置的 DDL 事件处理器
   */
  def withConfig(
    config: DDLProcessingConfig
  )(implicit system: ActorSystem[_]): DDLEventProcessor = {
    
    implicit val ec: ExecutionContext = system.executionContext
    
    val ddlHandler = new DDLHandler(config.strategy)
    val alertManager = new DDLAlertManager()
    
    apply(ddlHandler, alertManager)
  }
}