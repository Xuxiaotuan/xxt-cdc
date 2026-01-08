package cn.xuyinyin.cdc.metrics

import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, ScheduledExecutorService, ScheduledFuture, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/**
 * 增强的指标收集器
 * 整合 CDC 指标和 Prometheus 指标
 */
class EnhancedMetricsCollector(
  cdcMetrics: CDCMetrics,
  prometheusMetrics: PrometheusMetrics,
  scheduler: ScheduledExecutorService
)(implicit ec: ExecutionContext) extends LazyLogging {
  
  // ========== 表级指标 ==========
  private val tableStats = new ConcurrentHashMap[TableId, TableStatistics]()
  
  // ========== 热表集指标 ==========
  private val hotSetStats = new AtomicReference[HotSetStatistics](HotSetStatistics.empty)
  
  // ========== 快照指标 ==========
  private val snapshotStats = new ConcurrentHashMap[String, SnapshotStatistics]()
  
  // ========== 连接池指标 ==========
  private val connectionPoolStats = new ConcurrentHashMap[String, ConnectionPoolStatistics]()
  
  // ========== DDL 指标 ==========
  private val ddlStats = new AtomicReference[DDLStatistics](DDLStatistics.empty)
  
  // ========== 系统健康状态 ==========
  private val systemHealth = new AtomicReference[SystemHealth](SystemHealth.healthy())
  
  // 定期更新任务
  private var metricsUpdateTask: Option[ScheduledFuture[_]] = None
  
  /**
   * 启动指标收集
   */
  def start(updateIntervalSeconds: Int = 10): Future[Unit] = {
    Future {
      // 启动 Prometheus HTTP 服务器
      prometheusMetrics.startHttpServer()
      
      // 启动定期更新任务
      val task = scheduler.scheduleAtFixedRate(
        () => updateAllMetrics(),
        0,
        updateIntervalSeconds,
        TimeUnit.SECONDS
      )
      metricsUpdateTask = Some(task)
      
      logger.info(s"Enhanced metrics collector started with update interval ${updateIntervalSeconds}s")
    }
  }
  
  /**
   * 停止指标收集
   */
  def stop(): Future[Unit] = {
    Future {
      metricsUpdateTask.foreach(_.cancel(false))
      metricsUpdateTask = None
      
      prometheusMetrics.stopHttpServer()
      
      logger.info("Enhanced metrics collector stopped")
    }
  }
  
  // ========== 事件记录方法 ==========
  
  /**
   * 记录事件接收
   */
  def recordIngest(tableId: TableId, position: BinlogPosition, timestamp: Instant): Unit = {
    // 更新基础指标
    cdcMetrics.recordIngest(position, timestamp)
    prometheusMetrics.recordIngest(tableId, position, timestamp)
    
    // 更新表级指标
    getOrCreateTableStats(tableId).recordIngest(timestamp)
  }
  
  /**
   * 记录事件应用
   */
  def recordApply(tableId: TableId, eventType: String, count: Int = 1): Unit = {
    // 更新基础指标
    cdcMetrics.recordApply(count)
    prometheusMetrics.recordApply(tableId, eventType, count)
    
    // 更新表级指标
    getOrCreateTableStats(tableId).recordApply(eventType, count)
  }
  
  /**
   * 记录错误
   */
  def recordError(component: String, errorType: String, tableId: Option[TableId] = None): Unit = {
    // 更新基础指标
    cdcMetrics.recordError()
    prometheusMetrics.recordError(component, errorType)
    
    // 更新表级指标
    tableId.foreach(getOrCreateTableStats(_).recordError(errorType))
  }
  
  /**
   * 记录处理延迟
   */
  def recordProcessingLatency(component: String, latencyMs: Long): Unit = {
    val latencySeconds = latencyMs / 1000.0
    prometheusMetrics.recordProcessingLatency(component, latencySeconds)
  }
  
  /**
   * 更新队列深度
   */
  def updateQueueDepth(queueType: String, depth: Long): Unit = {
    cdcMetrics.updateQueueDepth(depth)
    prometheusMetrics.updateQueueDepth(queueType, depth)
  }
  
  // ========== 热表集指标 ==========
  
  /**
   * 更新热表集指标
   */
  def updateHotSetMetrics(
    activeTableCount: Int,
    memoryUsageBytes: Long,
    avgTemperature: Double,
    maxTemperature: Double
  ): Unit = {
    val stats = HotSetStatistics(
      activeTableCount = activeTableCount,
      memoryUsageBytes = memoryUsageBytes,
      avgTemperature = avgTemperature,
      maxTemperature = maxTemperature,
      lastUpdated = Instant.now()
    )
    
    hotSetStats.set(stats)
    prometheusMetrics.updateHotSetMetrics(activeTableCount, memoryUsageBytes)
  }
  
  /**
   * 更新表温度
   */
  def updateTableTemperature(tableId: TableId, temperature: Double): Unit = {
    prometheusMetrics.updateTableTemperature(tableId, temperature)
    getOrCreateTableStats(tableId).updateTemperature(temperature)
  }
  
  // ========== 快照指标 ==========
  
  /**
   * 记录快照开始
   */
  def recordSnapshotStart(snapshotId: String, tableId: TableId): Unit = {
    val stats = SnapshotStatistics(
      snapshotId = snapshotId,
      tableId = tableId,
      status = "running",
      startTime = Some(Instant.now()),
      endTime = None,
      rowCount = 0,
      durationMs = 0
    )
    
    snapshotStats.put(snapshotId, stats)
    prometheusMetrics.recordSnapshot(tableId, "started", 0, 0)
  }
  
  /**
   * 记录快照完成
   */
  def recordSnapshotComplete(
    snapshotId: String,
    tableId: TableId,
    rowCount: Long,
    success: Boolean
  ): Unit = {
    snapshotStats.get(snapshotId) match {
      case stats if stats != null =>
        val endTime = Instant.now()
        val durationMs = stats.startTime.map { start =>
          java.time.Duration.between(start, endTime).toMillis
        }.getOrElse(0L)
        
        val updatedStats = stats.copy(
          status = if (success) "completed" else "failed",
          endTime = Some(endTime),
          rowCount = rowCount,
          durationMs = durationMs
        )
        
        snapshotStats.put(snapshotId, updatedStats)
        
        val status = if (success) "completed" else "failed"
        prometheusMetrics.recordSnapshot(tableId, status, durationMs / 1000.0, rowCount)
        
      case _ =>
        logger.warn(s"Snapshot $snapshotId not found in statistics")
    }
  }
  
  // ========== 连接池指标 ==========
  
  /**
   * 更新连接池指标
   */
  def updateConnectionPoolMetrics(
    poolName: String,
    active: Int,
    idle: Int,
    waiting: Int,
    maxPoolSize: Int
  ): Unit = {
    val stats = ConnectionPoolStatistics(
      poolName = poolName,
      activeConnections = active,
      idleConnections = idle,
      waitingThreads = waiting,
      maxPoolSize = maxPoolSize,
      utilizationRate = active.toDouble / maxPoolSize,
      lastUpdated = Instant.now()
    )
    
    connectionPoolStats.put(poolName, stats)
    prometheusMetrics.updateConnectionPoolMetrics(poolName, active, idle, waiting)
  }
  
  // ========== DDL 指标 ==========
  
  /**
   * 记录 DDL 事件
   */
  def recordDDLEvent(
    tableId: Option[TableId],
    ddlType: String,
    status: String
  ): Unit = {
    prometheusMetrics.recordDDLEvent(tableId, ddlType, status)
    
    val currentStats = ddlStats.get()
    val updatedStats = currentStats.recordEvent(ddlType, status)
    ddlStats.set(updatedStats)
  }
  
  // ========== 系统健康指标 ==========
  
  /**
   * 更新系统健康状态
   */
  def updateSystemHealth(
    isHealthy: Boolean,
    issues: Seq[String] = Seq.empty,
    lastHealthCheck: Instant = Instant.now()
  ): Unit = {
    val health = SystemHealth(
      isHealthy = isHealthy,
      issues = issues,
      lastHealthCheck = lastHealthCheck
    )
    
    systemHealth.set(health)
    prometheusMetrics.setSystemStatus(isHealthy)
  }
  
  // ========== 指标查询方法 ==========
  
  /**
   * 获取完整的指标摘要
   */
  def getMetricsSummary(): CompleteMetricsSummary = {
    val cdcSnapshot = cdcMetrics.getSnapshot()
    val tableMetrics = tableStats.asScala.map { case (tableId, stats) =>
      tableId -> stats.toMap()
    }.toMap
    
    CompleteMetricsSummary(
      basic = cdcSnapshot,
      tables = tableMetrics,
      hotSet = hotSetStats.get(),
      snapshots = snapshotStats.asScala.toMap,
      connectionPools = connectionPoolStats.asScala.toMap,
      ddl = ddlStats.get(),
      systemHealth = systemHealth.get()
    )
  }
  
  /**
   * 获取表级指标
   */
  def getTableMetrics(tableId: TableId): Option[TableStatistics] = {
    Option(tableStats.get(tableId))
  }
  
  /**
   * 获取所有表的指标
   */
  def getAllTableMetrics(): Map[TableId, TableStatistics] = {
    tableStats.asScala.toMap
  }
  
  /**
   * 获取热表集指标
   */
  def getHotSetMetrics(): HotSetStatistics = {
    hotSetStats.get()
  }
  
  /**
   * 获取快照指标
   */
  def getSnapshotMetrics(): Map[String, SnapshotStatistics] = {
    snapshotStats.asScala.toMap
  }
  
  /**
   * 获取连接池指标
   */
  def getConnectionPoolMetrics(): Map[String, ConnectionPoolStatistics] = {
    connectionPoolStats.asScala.toMap
  }
  
  /**
   * 获取 DDL 指标
   */
  def getDDLMetrics(): DDLStatistics = {
    ddlStats.get()
  }
  
  /**
   * 获取系统健康状态
   */
  def getSystemHealth(): SystemHealth = {
    systemHealth.get()
  }
  
  // ========== 私有方法 ==========
  
  private def getOrCreateTableStats(tableId: TableId): TableStatistics = {
    tableStats.computeIfAbsent(tableId, _ => TableStatistics.empty(tableId))
  }
  
  private def updateAllMetrics(): Unit = {
    try {
      // 更新系统运行时间
      prometheusMetrics.updateUptime()
      
      // 更新 TPS 指标
      val snapshot = cdcMetrics.getSnapshot()
      prometheusMetrics.updateRates(snapshot.ingestTPS, snapshot.applyTPS)
      
      // 清理过期的表指标
      cleanupInactiveTableMetrics()
      
    } catch {
      case ex: Exception =>
        logger.error(s"Error updating metrics: ${ex.getMessage}", ex)
    }
  }
  
  private def cleanupInactiveTableMetrics(inactiveThresholdMinutes: Int = 60): Unit = {
    val cutoffTime = Instant.now().minusSeconds(inactiveThresholdMinutes * 60)
    
    val toRemove = tableStats.asScala.filter { case (_, stats) =>
      stats.lastActivity.isBefore(cutoffTime)
    }.keys.toSeq
    
    toRemove.foreach(tableStats.remove)
    
    if (toRemove.nonEmpty) {
      logger.debug(s"Cleaned up ${toRemove.size} inactive table metrics")
    }
  }
}

// ========== 数据类 ==========

/**
 * 表级统计信息
 */
case class TableStatistics(
  tableId: TableId,
  ingestCount: Long,
  applyCount: Long,
  errorCount: Long,
  lastIngestTime: Option[Instant],
  lastApplyTime: Option[Instant],
  lastActivity: Instant,
  temperature: Double,
  eventTypeCounts: Map[String, Long],
  errorTypeCounts: Map[String, Long]
) {
  def recordIngest(timestamp: Instant): TableStatistics = {
    copy(
      ingestCount = ingestCount + 1,
      lastIngestTime = Some(timestamp),
      lastActivity = Instant.now()
    )
  }
  
  def recordApply(eventType: String, count: Int): TableStatistics = {
    copy(
      applyCount = applyCount + count,
      lastApplyTime = Some(Instant.now()),
      lastActivity = Instant.now(),
      eventTypeCounts = eventTypeCounts + (eventType -> (eventTypeCounts.getOrElse(eventType, 0L) + count))
    )
  }
  
  def recordError(errorType: String): TableStatistics = {
    copy(
      errorCount = errorCount + 1,
      lastActivity = Instant.now(),
      errorTypeCounts = errorTypeCounts + (errorType -> (errorTypeCounts.getOrElse(errorType, 0L) + 1))
    )
  }
  
  def updateTemperature(newTemperature: Double): TableStatistics = {
    copy(temperature = newTemperature, lastActivity = Instant.now())
  }
  
  def toMap(): Map[String, Any] = Map(
    "tableId" -> tableId.toString,
    "ingestCount" -> ingestCount,
    "applyCount" -> applyCount,
    "errorCount" -> errorCount,
    "lastIngestTime" -> lastIngestTime.map(_.toString).orNull,
    "lastApplyTime" -> lastApplyTime.map(_.toString).orNull,
    "lastActivity" -> lastActivity.toString,
    "temperature" -> temperature,
    "eventTypeCounts" -> eventTypeCounts,
    "errorTypeCounts" -> errorTypeCounts
  )
}

object TableStatistics {
  def empty(tableId: TableId): TableStatistics = {
    TableStatistics(
      tableId = tableId,
      ingestCount = 0,
      applyCount = 0,
      errorCount = 0,
      lastIngestTime = None,
      lastApplyTime = None,
      lastActivity = Instant.now(),
      temperature = 0.0,
      eventTypeCounts = Map.empty,
      errorTypeCounts = Map.empty
    )
  }
}

/**
 * 热表集统计信息
 */
case class HotSetStatistics(
  activeTableCount: Int,
  memoryUsageBytes: Long,
  avgTemperature: Double,
  maxTemperature: Double,
  lastUpdated: Instant
)

object HotSetStatistics {
  def empty: HotSetStatistics = {
    HotSetStatistics(0, 0, 0.0, 0.0, Instant.now())
  }
}

/**
 * 快照统计信息
 */
case class SnapshotStatistics(
  snapshotId: String,
  tableId: TableId,
  status: String,
  startTime: Option[Instant],
  endTime: Option[Instant],
  rowCount: Long,
  durationMs: Long
)

/**
 * 连接池统计信息
 */
case class ConnectionPoolStatistics(
  poolName: String,
  activeConnections: Int,
  idleConnections: Int,
  waitingThreads: Int,
  maxPoolSize: Int,
  utilizationRate: Double,
  lastUpdated: Instant
)

/**
 * DDL 统计信息
 */
case class DDLStatistics(
  totalEvents: Long,
  eventTypeCounts: Map[String, Long],
  statusCounts: Map[String, Long],
  lastEventTime: Option[Instant]
) {
  def recordEvent(ddlType: String, status: String): DDLStatistics = {
    copy(
      totalEvents = totalEvents + 1,
      eventTypeCounts = eventTypeCounts + (ddlType -> (eventTypeCounts.getOrElse(ddlType, 0L) + 1)),
      statusCounts = statusCounts + (status -> (statusCounts.getOrElse(status, 0L) + 1)),
      lastEventTime = Some(Instant.now())
    )
  }
}

object DDLStatistics {
  def empty: DDLStatistics = {
    DDLStatistics(0, Map.empty, Map.empty, None)
  }
}

/**
 * 系统健康状态
 */
case class SystemHealth(
  isHealthy: Boolean,
  issues: Seq[String],
  lastHealthCheck: Instant
)

object SystemHealth {
  def healthy(): SystemHealth = {
    SystemHealth(isHealthy = true, issues = Seq.empty, lastHealthCheck = Instant.now())
  }
  
  def unhealthy(issues: Seq[String]): SystemHealth = {
    SystemHealth(isHealthy = false, issues = issues, lastHealthCheck = Instant.now())
  }
}

/**
 * 完整的指标摘要
 */
case class CompleteMetricsSummary(
  basic: MetricsSnapshot,
  tables: Map[TableId, Map[String, Any]],
  hotSet: HotSetStatistics,
  snapshots: Map[String, SnapshotStatistics],
  connectionPools: Map[String, ConnectionPoolStatistics],
  ddl: DDLStatistics,
  systemHealth: SystemHealth
)

object EnhancedMetricsCollector {
  /**
   * 创建增强的指标收集器
   */
  def apply(
    cdcMetrics: CDCMetrics,
    prometheusMetrics: PrometheusMetrics,
    scheduler: ScheduledExecutorService
  )(implicit ec: ExecutionContext): EnhancedMetricsCollector = {
    new EnhancedMetricsCollector(cdcMetrics, prometheusMetrics, scheduler)
  }
}