package cn.xuyinyin.cdc.metrics

import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client._
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/**
 * Prometheus 指标收集器
 * 提供 Prometheus 格式的指标导出
 */
class PrometheusMetrics(
  namespace: String = "cdc",
  port: Int = 9090
)(implicit ec: ExecutionContext) extends LazyLogging {
  
  // ========== 基础指标 ==========
  
  // 事件计数器
  private val ingestCounter = Counter.build()
    .namespace(namespace)
    .name("events_ingested_total")
    .help("Total number of events ingested from binlog")
    .labelNames("database", "table")
    .register()
  
  private val applyCounter = Counter.build()
    .namespace(namespace)
    .name("events_applied_total")
    .help("Total number of events applied to target")
    .labelNames("database", "table", "event_type")
    .register()
  
  private val errorCounter = Counter.build()
    .namespace(namespace)
    .name("errors_total")
    .help("Total number of errors")
    .labelNames("component", "error_type")
    .register()
  
  // TPS 指标
  private val ingestRate = Gauge.build()
    .namespace(namespace)
    .name("ingest_rate_events_per_second")
    .help("Current ingest rate in events per second")
    .register()
  
  private val applyRate = Gauge.build()
    .namespace(namespace)
    .name("apply_rate_events_per_second")
    .help("Current apply rate in events per second")
    .register()
  
  // 延迟指标
  private val binlogLag = Gauge.build()
    .namespace(namespace)
    .name("binlog_lag_seconds")
    .help("Binlog lag in seconds")
    .register()
  
  private val processingLatency = Histogram.build()
    .namespace(namespace)
    .name("processing_latency_seconds")
    .help("Event processing latency")
    .labelNames("component")
    .register()
  
  // 队列深度指标
  private val queueDepth = Gauge.build()
    .namespace(namespace)
    .name("queue_depth")
    .help("Current queue depth")
    .labelNames("queue_type")
    .register()
  
  // ========== 热表集指标 ==========
  
  private val hotSetSize = Gauge.build()
    .namespace(namespace)
    .name("hot_set_size")
    .help("Number of tables in hot set")
    .register()
  
  private val hotSetMemoryUsage = Gauge.build()
    .namespace(namespace)
    .name("hot_set_memory_bytes")
    .help("Memory usage of hot set in bytes")
    .register()
  
  private val tableTemperature = Gauge.build()
    .namespace(namespace)
    .name("table_temperature")
    .help("Table temperature (activity level)")
    .labelNames("database", "table")
    .register()
  
  // ========== 快照指标 ==========
  
  private val snapshotCounter = Counter.build()
    .namespace(namespace)
    .name("snapshots_total")
    .help("Total number of snapshots")
    .labelNames("database", "table", "status")
    .register()
  
  private val snapshotDuration = Histogram.build()
    .namespace(namespace)
    .name("snapshot_duration_seconds")
    .help("Snapshot duration in seconds")
    .labelNames("database", "table")
    .register()
  
  private val snapshotRows = Histogram.build()
    .namespace(namespace)
    .name("snapshot_rows")
    .help("Number of rows in snapshot")
    .labelNames("database", "table")
    .register()
  
  // ========== 连接池指标 ==========
  
  private val connectionPoolActive = Gauge.build()
    .namespace(namespace)
    .name("connection_pool_active")
    .help("Number of active connections")
    .labelNames("pool_name")
    .register()
  
  private val connectionPoolIdle = Gauge.build()
    .namespace(namespace)
    .name("connection_pool_idle")
    .help("Number of idle connections")
    .labelNames("pool_name")
    .register()
  
  private val connectionPoolWaiting = Gauge.build()
    .namespace(namespace)
    .name("connection_pool_waiting")
    .help("Number of threads waiting for connections")
    .labelNames("pool_name")
    .register()
  
  // ========== DDL 指标 ==========
  
  private val ddlCounter = Counter.build()
    .namespace(namespace)
    .name("ddl_events_total")
    .help("Total number of DDL events")
    .labelNames("database", "table", "ddl_type", "status")
    .register()
  
  // ========== 系统指标 ==========
  
  private val systemUptime = Gauge.build()
    .namespace(namespace)
    .name("uptime_seconds")
    .help("System uptime in seconds")
    .register()
  
  private val systemStatus = Gauge.build()
    .namespace(namespace)
    .name("system_status")
    .help("System status (1=healthy, 0=unhealthy)")
    .register()
  
  // HTTP 服务器
  private var httpServer: Option[HTTPServer] = None
  
  // 启动时间
  private val startTime = Instant.now()
  
  // 表级指标缓存
  private val tableMetrics = new ConcurrentHashMap[TableId, TableMetrics]()
  
  /**
   * 启动 Prometheus HTTP 服务器
   */
  def startHttpServer(): Future[Unit] = {
    Future {
      try {
        // 注册 JVM 指标
        DefaultExports.initialize()
        
        // 启动 HTTP 服务器
        httpServer = Some(new HTTPServer(port))
        logger.info(s"Prometheus metrics server started on port $port")
        
        // 设置系统状态为健康
        systemStatus.set(1.0)
        
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to start Prometheus HTTP server: ${ex.getMessage}", ex)
          throw ex
      }
    }
  }

  /**
   * 停止 Prometheus 指标 HTTP 服务器
   */
  def stopHttpServer(): Unit = {
    httpServer.foreach { server =>
      try {
        // 使用推荐的新方法 `close()` 替代已弃用的 `stop()` 方法
        server.close()
        // 只有在未抛出异常时才打印成功日志
        logger.info("Prometheus metrics HTTP server stopped successfully")
      } catch {
        case ex: Exception =>
          logger.error(s"Error stopping Prometheus HTTP server: ${ex.getMessage}", ex)
      }
    }
    // 无论停止成功与否，都清空引用，允许资源被GC回收
    httpServer = None
  }
  
  /**
   * 记录事件接收
   */
  def recordIngest(tableId: TableId, position: BinlogPosition, timestamp: Instant): Unit = {
    ingestCounter.labels(tableId.database, tableId.table).inc()
    
    // 更新 binlog lag
    val lag = java.time.Duration.between(timestamp, Instant.now()).getSeconds
    binlogLag.set(lag.toDouble)
    
    // 更新表级指标
    getOrCreateTableMetrics(tableId).recordIngest()
  }
  
  /**
   * 记录事件应用
   */
  def recordApply(tableId: TableId, eventType: String, count: Int = 1): Unit = {
    applyCounter.labels(tableId.database, tableId.table, eventType).inc(count.toDouble)
    getOrCreateTableMetrics(tableId).recordApply(count)
  }
  
  /**
   * 记录错误
   */
  def recordError(component: String, errorType: String): Unit = {
    errorCounter.labels(component, errorType).inc()
  }
  
  /**
   * 记录处理延迟
   */
  def recordProcessingLatency(component: String, latencySeconds: Double): Unit = {
    processingLatency.labels(component).observe(latencySeconds)
  }
  
  /**
   * 更新队列深度
   */
  def updateQueueDepth(queueType: String, depth: Long): Unit = {
    queueDepth.labels(queueType).set(depth.toDouble)
  }
  
  /**
   * 更新热表集指标
   */
  def updateHotSetMetrics(size: Int, memoryUsage: Long): Unit = {
    hotSetSize.set(size.toDouble)
    hotSetMemoryUsage.set(memoryUsage.toDouble)
  }
  
  /**
   * 更新表温度
   */
  def updateTableTemperature(tableId: TableId, temperature: Double): Unit = {
    tableTemperature.labels(tableId.database, tableId.table).set(temperature)
  }
  
  /**
   * 记录快照指标
   */
  def recordSnapshot(
    tableId: TableId,
    status: String,
    durationSeconds: Double,
    rowCount: Long
  ): Unit = {
    snapshotCounter.labels(tableId.database, tableId.table, status).inc()
    
    if (status == "completed") {
      snapshotDuration.labels(tableId.database, tableId.table).observe(durationSeconds)
      snapshotRows.labels(tableId.database, tableId.table).observe(rowCount.toDouble)
    }
  }
  
  /**
   * 更新连接池指标
   */
  def updateConnectionPoolMetrics(
    poolName: String,
    active: Int,
    idle: Int,
    waiting: Int
  ): Unit = {
    connectionPoolActive.labels(poolName).set(active.toDouble)
    connectionPoolIdle.labels(poolName).set(idle.toDouble)
    connectionPoolWaiting.labels(poolName).set(waiting.toDouble)
  }
  
  /**
   * 记录 DDL 事件
   */
  def recordDDLEvent(
    tableId: Option[TableId],
    ddlType: String,
    status: String
  ): Unit = {
    val database = tableId.map(_.database).getOrElse("unknown")
    val table = tableId.map(_.table).getOrElse("unknown")
    ddlCounter.labels(database, table, ddlType, status).inc()
  }
  
  /**
   * 更新 TPS 指标
   */
  def updateRates(ingestTPS: Double, applyTPS: Double): Unit = {
    ingestRate.set(ingestTPS)
    applyRate.set(applyTPS)
  }
  
  /**
   * 更新系统运行时间
   */
  def updateUptime(): Unit = {
    val uptime = java.time.Duration.between(startTime, Instant.now()).getSeconds
    systemUptime.set(uptime.toDouble)
  }
  
  /**
   * 设置系统状态
   */
  def setSystemStatus(healthy: Boolean): Unit = {
    systemStatus.set(if (healthy) 1.0 else 0.0)
  }
  
  /**
   * 获取或创建表级指标
   */
  private def getOrCreateTableMetrics(tableId: TableId): TableMetrics = {
    tableMetrics.computeIfAbsent(tableId, _ => new TableMetrics())
  }
  
  /**
   * 获取所有表的指标摘要
   */
  def getTableMetricsSummary(): Map[TableId, Map[String, Any]] = {
    tableMetrics.asScala.map { case (tableId, metrics) =>
      tableId -> metrics.getSummary()
    }.toMap
  }
  
  /**
   * 清理不活跃的表指标
   */
  def cleanupInactiveTableMetrics(inactiveThresholdMinutes: Int = 60): Int = {
    val cutoffTime = Instant.now().minusSeconds(inactiveThresholdMinutes * 60)
    val toRemove = tableMetrics.asScala.filter { case (_, metrics) =>
      metrics.getLastActivity().isBefore(cutoffTime)
    }.keys.toSeq
    
    toRemove.foreach(tableMetrics.remove)
    
    if (toRemove.nonEmpty) {
      logger.info(s"Cleaned up ${toRemove.size} inactive table metrics")
    }
    
    toRemove.size
  }
}

/**
 * 表级指标
 */
private class TableMetrics {
  private var ingestCount = 0L
  private var applyCount = 0L
  private var lastActivity = Instant.now()
  
  def recordIngest(): Unit = {
    ingestCount += 1
    lastActivity = Instant.now()
  }
  
  def recordApply(count: Int): Unit = {
    applyCount += count
    lastActivity = Instant.now()
  }
  
  def getLastActivity(): Instant = lastActivity
  
  def getSummary(): Map[String, Any] = Map(
    "ingestCount" -> ingestCount,
    "applyCount" -> applyCount,
    "lastActivity" -> lastActivity.toString
  )
}

/**
 * Prometheus 指标管理器
 */
class PrometheusMetricsManager(
  prometheusMetrics: PrometheusMetrics,
  cdcMetrics: CDCMetrics
)(implicit ec: ExecutionContext) extends LazyLogging {
  
  private var updateTask: Option[java.util.concurrent.ScheduledFuture[_]] = None
  private val scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1)
  
  /**
   * 启动指标更新任务
   */
  def startMetricsUpdate(intervalSeconds: Int = 10): Unit = {
    val task = scheduler.scheduleAtFixedRate(
      () => updateMetrics(),
      0,
      intervalSeconds,
      java.util.concurrent.TimeUnit.SECONDS
    )
    updateTask = Some(task)
    
    logger.info(s"Started Prometheus metrics update task with interval ${intervalSeconds}s")
  }
  
  /**
   * 停止指标更新任务
   */
  def stopMetricsUpdate(): Unit = {
    updateTask.foreach(_.cancel(false))
    updateTask = None
    logger.info("Stopped Prometheus metrics update task")
  }
  
  /**
   * 更新指标
   */
  private def updateMetrics(): Unit = {
    try {
      val snapshot = cdcMetrics.getSnapshot()
      
      // 更新 TPS 指标
      prometheusMetrics.updateRates(snapshot.ingestTPS, snapshot.applyTPS)
      
      // 更新队列深度
      prometheusMetrics.updateQueueDepth("main", snapshot.queueDepth)
      
      // 更新系统运行时间
      prometheusMetrics.updateUptime()
      
      // 清理不活跃的表指标
      prometheusMetrics.cleanupInactiveTableMetrics()
      
    } catch {
      case ex: Exception =>
        logger.error(s"Error updating Prometheus metrics: ${ex.getMessage}", ex)
    }
  }
  
  /**
   * 关闭管理器
   */
  def shutdown(): Unit = {
    stopMetricsUpdate()
    scheduler.shutdown()
    prometheusMetrics.stopHttpServer()
  }
}

object PrometheusMetrics {
  /**
   * 创建 Prometheus 指标收集器
   */
  def apply(
    namespace: String = "cdc",
    port: Int = 9090
  )(implicit ec: ExecutionContext): PrometheusMetrics = {
    new PrometheusMetrics(namespace, port)
  }
}

object PrometheusMetricsManager {
  /**
   * 创建 Prometheus 指标管理器
   */
  def apply(
    prometheusMetrics: PrometheusMetrics,
    cdcMetrics: CDCMetrics
  )(implicit ec: ExecutionContext): PrometheusMetricsManager = {
    new PrometheusMetricsManager(prometheusMetrics, cdcMetrics)
  }
}