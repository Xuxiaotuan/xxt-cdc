package cn.xuyinyin.cdc.logging

import cn.xuyinyin.cdc.metrics.CDCMetrics

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * 性能指标定期日志输出器
 * 
 * 定期（默认每 10 秒）输出 CDC 系统的性能指标，包括：
 * - Ingest TPS: binlog 读取速率
 * - Apply TPS: 目标库写入速率
 * - Binlog Lag: binlog 延迟（毫秒和事件数）
 * - Queue Depth: 队列深度
 * - Hot Tables: 热表数量
 * - Error Rate: 错误率
 */
class PerformanceLogger(
  metrics: CDCMetrics,
  interval: FiniteDuration = 60.seconds
)(implicit ec: ExecutionContext) extends CDCLogging {
  
  @volatile private var isRunning = false
  private var scheduledTask: Option[Future[Unit]] = None
  
  /**
   * 启动定期日志输出
   */
  def start(): Unit = {
    if (!isRunning) {
      isRunning = true
      scheduleNextLog()
      logger.info(s"Performance logger started, interval: ${interval.toSeconds}s")
    }
  }
  
  /**
   * 停止定期日志输出
   */
  def stop(): Unit = {
    isRunning = false
    logger.info("Performance logger stopped")
  }
  
  private def scheduleNextLog(): Unit = {
    if (isRunning) {
      scheduledTask = Some(Future {
        Thread.sleep(interval.toMillis)
        if (isRunning) {
          logPerformanceMetrics()
          scheduleNextLog()
        }
      })
    }
  }
  
  private def logPerformanceMetrics(): Unit = {
    val snapshot = metrics.getSnapshot()
    
    val ingestTps = snapshot.ingestTPS  // 保留小数，不转换成 Long
    val applyTps = snapshot.applyTPS    // 保留小数，不转换成 Long
    val lagMs = snapshot.binlogLag.map(_.toMillis).getOrElse(0L)
    val lagEvents = snapshot.ingestCount - snapshot.applyCount
    val queueDepth = snapshot.queueDepth.toInt
    val queueCapacity = snapshot.maxQueueDepth.toInt
    val errorRate = snapshot.errorRate * 100
    
    // 新增的指标
    val ingestCount = snapshot.ingestCount
    val applyCount = snapshot.applyCount
    val uptime = snapshot.uptime
    
    val queueUsage = if (queueCapacity > 0) {
      f"${(queueDepth.toDouble / queueCapacity * 100)}%.1f%%"
    } else {
      "N/A"
    }
    
    // 使用多行字符串，每行单独输出
    logger.info(s"${LogColors.CYAN}╔════════════════════════════════════════════════════════════╗${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║           CDC Performance Metrics                          ║${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}╠════════════════════════════════════════════════════════════╣${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Total Events:    ${formatTotalEvents(ingestCount, applyCount)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Ingest TPS:      ${formatTpsMetric(ingestTps)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Apply TPS:       ${formatTpsMetric(applyTps)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Binlog Lag:      ${formatLag(lagMs, lagEvents)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Queue Depth:     ${formatQueue(queueDepth, queueCapacity, queueUsage)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Error Rate:      ${formatErrorRate(errorRate)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}║ Uptime:          ${formatUptime(uptime)}${LogColors.RESET}")
    logger.info(s"${LogColors.CYAN}╚════════════════════════════════════════════════════════════╝${LogColors.RESET}")
  }
  
  private def formatTotalEvents(ingestCount: Long, applyCount: Long): String = {
    val text = f"Ingested: $ingestCount%,d | Applied: $applyCount%,d"
    val padding = " " * (40 - text.length)
    s"$text$padding║"
  }
  
  private def formatTpsMetric(tps: Double): String = {
    val text = f"$tps%.2f events/s (avg since start)"
    val padding = " " * (40 - text.length)
    s"$text$padding║"
  }
  
  private def formatLag(lagMs: Long, lagEvents: Long): String = {
    val lagStr = if (lagMs < 1000) {
      s"${lagMs}ms"
    } else if (lagMs < 60000) {
      f"${lagMs / 1000.0}%.1fs"
    } else {
      f"${lagMs / 60000.0}%.1fm"
    }
    
    val eventsStr = if (lagEvents == 0) {
      "(idle)"
    } else {
      f"($lagEvents%,d pending)"
    }
    
    val combined = s"$lagStr $eventsStr"
    val padding = " " * (40 - combined.length)
    s"$combined$padding║"
  }
  
  private def formatQueue(depth: Int, capacity: Int, usage: String): String = {
    val queueStr = f"$depth%,d / $capacity%,d ($usage)"
    val padding = " " * (40 - queueStr.length)
    s"$queueStr$padding║"
  }
  
  private def formatErrorRate(rate: Double): String = {
    val color = if (rate > 5.0) {
      LogColors.RED
    } else if (rate > 1.0) {
      LogColors.YELLOW
    } else {
      LogColors.GREEN
    }
    
    val rateStr = f"$color$rate%.2f%%${LogColors.RESET}"
    val padding = " " * (40 - f"$rate%.2f%%".length)
    s"$rateStr$padding║"
  }
  
  private def formatUptime(uptime: Duration): String = {
    val totalSeconds = uptime.toSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    val text = if (hours > 0) {
      f"${hours}h ${minutes}m ${seconds}s"
    } else if (minutes > 0) {
      f"${minutes}m ${seconds}s"
    } else {
      f"${seconds}s"
    }
    
    val padding = " " * (40 - text.length)
    s"$text$padding║"
  }
}

object PerformanceLogger {
  def apply(metrics: CDCMetrics, interval: FiniteDuration = 60.seconds)
           (implicit ec: ExecutionContext): PerformanceLogger = {
    new PerformanceLogger(metrics, interval)
  }
}
