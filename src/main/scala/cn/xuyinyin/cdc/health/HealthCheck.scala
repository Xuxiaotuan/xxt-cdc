package cn.xuyinyin.cdc.health

import cn.xuyinyin.cdc.metrics.CDCMetrics
import cn.xuyinyin.cdc.model.CDCState

import java.time.Instant
import scala.concurrent.duration._

/**
 * 健康检查服务
 * 评估 CDC 系统的健康状态
 */
class HealthCheck(metrics: CDCMetrics) {
  
  // 健康检查阈值配置
  private val maxBinlogLag = 5.minutes
  private val maxErrorRate = 0.05 // 5%
  private val maxQueueDepthRatio = 0.8 // 80%
  
  /**
   * 执行健康检查
   */
  def check(currentState: CDCState, maxQueueSize: Int): HealthStatus = {
    val snapshot = metrics.getSnapshot()
    val checks = Seq(
      checkBinlogLag(snapshot),
      checkErrorRate(snapshot),
      checkQueueDepth(snapshot, maxQueueSize),
      checkState(currentState),
      checkProgress(snapshot)
    )
    
    val failedChecks = checks.filter(_.status == CheckStatus.Unhealthy)
    val warningChecks = checks.filter(_.status == CheckStatus.Warning)
    
    val overallStatus = if (failedChecks.nonEmpty) {
      HealthStatus.Unhealthy
    } else if (warningChecks.nonEmpty) {
      HealthStatus.Warning
    } else {
      HealthStatus.Healthy
    }
    
    HealthStatus(
      status = overallStatus,
      checks = checks,
      timestamp = Instant.now()
    )
  }
  
  private def checkBinlogLag(snapshot: cn.xuyinyin.cdc.metrics.MetricsSnapshot): HealthCheckResult = {
    snapshot.binlogLag match {
      case Some(lag) if lag > maxBinlogLag =>
        HealthCheckResult(
          name = "binlog_lag",
          status = CheckStatus.Unhealthy,
          message = s"Binlog lag is too high: ${lag.toSeconds}s (threshold: ${maxBinlogLag.toSeconds}s)"
        )
      case Some(lag) if lag > maxBinlogLag / 2 =>
        HealthCheckResult(
          name = "binlog_lag",
          status = CheckStatus.Warning,
          message = s"Binlog lag is elevated: ${lag.toSeconds}s"
        )
      case Some(lag) =>
        HealthCheckResult(
          name = "binlog_lag",
          status = CheckStatus.Healthy,
          message = s"Binlog lag is normal: ${lag.toSeconds}s"
        )
      case None =>
        HealthCheckResult(
          name = "binlog_lag",
          status = CheckStatus.Warning,
          message = "Binlog lag information not available"
        )
    }
  }
  
  private def checkErrorRate(snapshot: cn.xuyinyin.cdc.metrics.MetricsSnapshot): HealthCheckResult = {
    if (snapshot.errorRate > maxErrorRate) {
      HealthCheckResult(
        name = "error_rate",
        status = CheckStatus.Unhealthy,
        message = f"Error rate is too high: ${snapshot.errorRate * 100}%.2f%% (threshold: ${maxErrorRate * 100}%.2f%%)"
      )
    } else if (snapshot.errorRate > maxErrorRate / 2) {
      HealthCheckResult(
        name = "error_rate",
        status = CheckStatus.Warning,
        message = f"Error rate is elevated: ${snapshot.errorRate * 100}%.2f%%"
      )
    } else {
      HealthCheckResult(
        name = "error_rate",
        status = CheckStatus.Healthy,
        message = f"Error rate is normal: ${snapshot.errorRate * 100}%.2f%%"
      )
    }
  }
  
  private def checkQueueDepth(snapshot: cn.xuyinyin.cdc.metrics.MetricsSnapshot, maxSize: Int): HealthCheckResult = {
    val ratio = snapshot.queueDepth.toDouble / maxSize
    
    if (ratio > maxQueueDepthRatio) {
      HealthCheckResult(
        name = "queue_depth",
        status = CheckStatus.Unhealthy,
        message = f"Queue depth is too high: ${snapshot.queueDepth} / $maxSize (${ratio * 100}%.1f%%)"
      )
    } else if (ratio > maxQueueDepthRatio / 2) {
      HealthCheckResult(
        name = "queue_depth",
        status = CheckStatus.Warning,
        message = f"Queue depth is elevated: ${snapshot.queueDepth} / $maxSize (${ratio * 100}%.1f%%)"
      )
    } else {
      HealthCheckResult(
        name = "queue_depth",
        status = CheckStatus.Healthy,
        message = f"Queue depth is normal: ${snapshot.queueDepth} / $maxSize (${ratio * 100}%.1f%%)"
      )
    }
  }
  
  private def checkState(state: CDCState): HealthCheckResult = {
    state match {
      case cn.xuyinyin.cdc.model.Streaming =>
        HealthCheckResult(
          name = "cdc_state",
          status = CheckStatus.Healthy,
          message = s"CDC is in STREAMING state"
        )
      case cn.xuyinyin.cdc.model.Catchup =>
        HealthCheckResult(
          name = "cdc_state",
          status = CheckStatus.Warning,
          message = s"CDC is in CATCHUP state"
        )
      case _ =>
        HealthCheckResult(
          name = "cdc_state",
          status = CheckStatus.Warning,
          message = s"CDC is in ${state.name} state"
        )
    }
  }
  
  private def checkProgress(snapshot: cn.xuyinyin.cdc.metrics.MetricsSnapshot): HealthCheckResult = {
    val hasProgress = snapshot.ingestCount > 0 && snapshot.applyCount > 0
    
    if (hasProgress) {
      HealthCheckResult(
        name = "progress",
        status = CheckStatus.Healthy,
        message = s"CDC is making progress (ingested: ${snapshot.ingestCount}, applied: ${snapshot.applyCount})"
      )
    } else {
      HealthCheckResult(
        name = "progress",
        status = CheckStatus.Warning,
        message = "CDC has not processed any events yet"
      )
    }
  }
}

/**
 * 健康状态
 */
case class HealthStatus(
  status: HealthStatus.Value,
  checks: Seq[HealthCheckResult],
  timestamp: Instant
) {
  def toMap: Map[String, Any] = Map(
    "status" -> status.toString,
    "checks" -> checks.map(_.toMap),
    "timestamp" -> timestamp.toString
  )
}

object HealthStatus extends Enumeration {
  val Healthy, Warning, Unhealthy = Value
}

/**
 * 单个健康检查结果
 */
case class HealthCheckResult(
  name: String,
  status: CheckStatus.Value,
  message: String
) {
  def toMap: Map[String, String] = Map(
    "name" -> name,
    "status" -> status.toString,
    "message" -> message
  )
}

object CheckStatus extends Enumeration {
  val Healthy, Warning, Unhealthy = Value
}

object HealthCheck {
  /**
   * 创建 Health Check 实例
   */
  def apply(metrics: CDCMetrics): HealthCheck = new HealthCheck(metrics)
}
