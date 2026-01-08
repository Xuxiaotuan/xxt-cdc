package cn.xuyinyin.cdc.ddl

import cn.xuyinyin.cdc.model.TableId
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import scala.util.matching.Regex

/**
 * DDL 事件类型
 */
sealed trait DDLEventType {
  def name: String
  def isSupported: Boolean
}

case object CreateTable extends DDLEventType {
  override def name: String = "CREATE_TABLE"
  override def isSupported: Boolean = false
}

case object AlterTable extends DDLEventType {
  override def name: String = "ALTER_TABLE"
  override def isSupported: Boolean = false
}

case object DropTable extends DDLEventType {
  override def name: String = "DROP_TABLE"
  override def isSupported: Boolean = false
}

case object CreateIndex extends DDLEventType {
  override def name: String = "CREATE_INDEX"
  override def isSupported: Boolean = false
}

case object DropIndex extends DDLEventType {
  override def name: String = "DROP_INDEX"
  override def isSupported: Boolean = false
}

case object TruncateTable extends DDLEventType {
  override def name: String = "TRUNCATE_TABLE"
  override def isSupported: Boolean = false
}

case object RenameTable extends DDLEventType {
  override def name: String = "RENAME_TABLE"
  override def isSupported: Boolean = false
}

case object UnknownDDL extends DDLEventType {
  override def name: String = "UNKNOWN_DDL"
  override def isSupported: Boolean = false
}

/**
 * DDL 事件
 */
case class DDLEvent(
  eventType: DDLEventType,
  sql: String,
  database: Option[String],
  table: Option[TableId],
  timestamp: Instant,
  position: cn.xuyinyin.cdc.model.BinlogPosition
)

/**
 * DDL 处理策略
 */
sealed trait DDLHandlingStrategy
case object IgnoreDDL extends DDLHandlingStrategy
case object LogDDL extends DDLHandlingStrategy
case object AlertDDL extends DDLHandlingStrategy
case object FailOnDDL extends DDLHandlingStrategy

/**
 * DDL 处理结果
 */
sealed trait DDLHandlingResult
case object DDLIgnored extends DDLHandlingResult
case object DDLLogged extends DDLHandlingResult
case object DDLAlerted extends DDLHandlingResult
case class DDLFailed(reason: String) extends DDLHandlingResult

/**
 * DDL 告警
 */
case class DDLAlert(
  event: DDLEvent,
  severity: AlertSeverity,
  message: String,
  timestamp: Instant = Instant.now()
)

sealed trait AlertSeverity
case object Info extends AlertSeverity
case object Warning extends AlertSeverity
case object Critical extends AlertSeverity

/**
 * DDL 处理器
 * 负责检测、解析和处理 DDL 事件
 */
class DDLHandler(strategy: DDLHandlingStrategy = AlertDDL) extends LazyLogging {
  
  // DDL 模式匹配
  private val ddlPatterns = Map(
    CreateTable -> "(?i)^\\s*CREATE\\s+TABLE".r,
    AlterTable -> "(?i)^\\s*ALTER\\s+TABLE".r,
    DropTable -> "(?i)^\\s*DROP\\s+TABLE".r,
    CreateIndex -> "(?i)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX".r,
    DropIndex -> "(?i)^\\s*DROP\\s+INDEX".r,
    TruncateTable -> "(?i)^\\s*TRUNCATE\\s+TABLE".r,
    RenameTable -> "(?i)^\\s*RENAME\\s+TABLE".r
  )
  
  // 表名提取模式
  private val tableNamePatterns = Map(
    CreateTable -> "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:`?([^`\\s]+)`?\\.)?`?([^`\\s(]+)`?".r,
    AlterTable -> "(?i)ALTER\\s+TABLE\\s+(?:`?([^`\\s]+)`?\\.)?`?([^`\\s]+)`?".r,
    DropTable -> "(?i)DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:`?([^`\\s]+)`?\\.)?`?([^`\\s,]+)`?".r,
    TruncateTable -> "(?i)TRUNCATE\\s+TABLE\\s+(?:`?([^`\\s]+)`?\\.)?`?([^`\\s]+)`?".r,
    RenameTable -> "(?i)RENAME\\s+TABLE\\s+(?:`?([^`\\s]+)`?\\.)?`?([^`\\s]+)`?".r
  )
  
  // DDL 事件历史
  private val ddlHistory = scala.collection.mutable.ListBuffer[DDLEvent]()
  private val maxHistorySize = 1000
  
  // 告警列表
  private val alerts = scala.collection.mutable.ListBuffer[DDLAlert]()
  private val maxAlertsSize = 100
  
  /**
   * 检测 SQL 是否为 DDL
   */
  def isDDL(sql: String): Boolean = {
    ddlPatterns.values.exists(_.findFirstIn(sql.trim).isDefined)
  }
  
  /**
   * 解析 DDL 事件
   */
  def parseDDLEvent(
    sql: String,
    database: Option[String],
    timestamp: Instant,
    position: cn.xuyinyin.cdc.model.BinlogPosition
  ): Option[DDLEvent] = {
    
    if (!isDDL(sql)) {
      return None
    }
    
    val eventType = identifyDDLType(sql)
    val table = extractTableId(sql, eventType, database)
    
    val event = DDLEvent(
      eventType = eventType,
      sql = sql.trim,
      database = database,
      table = table,
      timestamp = timestamp,
      position = position
    )
    
    Some(event)
  }
  
  private def identifyDDLType(sql: String): DDLEventType = {
    ddlPatterns.find { case (_, pattern) =>
      pattern.findFirstIn(sql).isDefined
    }.map(_._1).getOrElse(UnknownDDL)
  }
  
  private def extractTableId(sql: String, eventType: DDLEventType, defaultDatabase: Option[String]): Option[TableId] = {
    val pattern = eventType match {
      case CreateTable => tableNamePatterns.get(CreateTable)
      case AlterTable => tableNamePatterns.get(AlterTable)
      case DropTable => tableNamePatterns.get(DropTable)
      case TruncateTable => tableNamePatterns.get(TruncateTable)
      case RenameTable => tableNamePatterns.get(RenameTable)
      case _ => None
    }
    
    pattern.flatMap { p =>
      p.findFirstMatchIn(sql).map { m =>
        val database = Option(m.group(1)).orElse(defaultDatabase).getOrElse("unknown")
        val table = m.group(2)
        TableId(database, table)
      }
    }
  }
  
  /**
   * 处理 DDL 事件
   */
  def handleDDLEvent(event: DDLEvent): DDLHandlingResult = {
    // 记录到历史
    addToHistory(event)
    
    logger.info(s"Detected DDL event: ${event.eventType.name} on ${event.table.getOrElse("unknown table")}")
    logger.debug(s"DDL SQL: ${event.sql}")
    
    strategy match {
      case IgnoreDDL =>
        logger.debug("Ignoring DDL event as per configuration")
        DDLIgnored
        
      case LogDDL =>
        logger.info(s"DDL Event: ${event.eventType.name} - ${event.sql}")
        DDLLogged
        
      case AlertDDL =>
        val alert = createAlert(event)
        addAlert(alert)
        logger.warn(s"DDL Alert: ${alert.message}")
        DDLAlerted
        
      case FailOnDDL =>
        val reason = s"DDL event detected: ${event.eventType.name} on ${event.table.getOrElse("unknown table")}"
        logger.error(s"Failing on DDL event: $reason")
        DDLFailed(reason)
    }
  }
  
  private def createAlert(event: DDLEvent): DDLAlert = {
    val severity = event.eventType match {
      case DropTable | TruncateTable => Critical
      case AlterTable => Warning
      case _ => Info
    }
    
    val message = event.table match {
      case Some(tableId) =>
        s"${event.eventType.name} detected on table ${tableId}: ${event.sql}"
      case None =>
        s"${event.eventType.name} detected: ${event.sql}"
    }
    
    DDLAlert(event, severity, message)
  }
  
  private def addToHistory(event: DDLEvent): Unit = {
    ddlHistory.synchronized {
      ddlHistory += event
      if (ddlHistory.size > maxHistorySize) {
        ddlHistory.remove(0, ddlHistory.size - maxHistorySize)
      }
    }
  }
  
  private def addAlert(alert: DDLAlert): Unit = {
    alerts.synchronized {
      alerts += alert
      if (alerts.size > maxAlertsSize) {
        alerts.remove(0, alerts.size - maxAlertsSize)
      }
    }
  }
  
  /**
   * 获取 DDL 事件历史
   */
  def getDDLHistory(limit: Int = 50): Seq[DDLEvent] = {
    ddlHistory.synchronized {
      ddlHistory.takeRight(limit).toSeq
    }
  }
  
  /**
   * 获取未处理的告警
   */
  def getAlerts(severity: Option[AlertSeverity] = None): Seq[DDLAlert] = {
    alerts.synchronized {
      val filtered = severity match {
        case Some(sev) => alerts.filter(_.severity == sev)
        case None => alerts
      }
      filtered.toSeq
    }
  }
  
  /**
   * 清除告警
   */
  def clearAlerts(): Unit = {
    alerts.synchronized {
      alerts.clear()
    }
  }
  
  /**
   * 获取 DDL 统计信息
   */
  def getDDLStatistics(): DDLStatistics = {
    ddlHistory.synchronized {
      val eventCounts = ddlHistory.groupBy(_.eventType).map { case (eventType, events) =>
        eventType.name -> events.size
      }
      
      val tableCounts = ddlHistory.flatMap(_.table).groupBy(identity).map { case (table, events) =>
        table.toString -> events.size
      }
      
      DDLStatistics(
        totalEvents = ddlHistory.size,
        eventTypeCounts = eventCounts.toMap,
        affectedTables = tableCounts.toMap,
        alertCount = alerts.size
      )
    }
  }
}

/**
 * DDL 统计信息
 */
case class DDLStatistics(
  totalEvents: Int,
  eventTypeCounts: Map[String, Int],
  affectedTables: Map[String, Int],
  alertCount: Int
) {
  def toMap: Map[String, Any] = Map(
    "totalEvents" -> totalEvents,
    "eventTypeCounts" -> eventTypeCounts,
    "affectedTables" -> affectedTables,
    "alertCount" -> alertCount
  )
}

/**
 * DDL 处理器工厂
 */
object DDLHandler {
  /**
   * 创建 DDL 处理器
   */
  def apply(strategy: DDLHandlingStrategy = AlertDDL): DDLHandler = {
    new DDLHandler(strategy)
  }
  
  /**
   * 从配置字符串创建策略
   */
  def strategyFromString(strategy: String): DDLHandlingStrategy = {
    strategy.toLowerCase match {
      case "ignore" => IgnoreDDL
      case "log" => LogDDL
      case "alert" => AlertDDL
      case "fail" => FailOnDDL
      case _ => AlertDDL // 默认策略
    }
  }
}