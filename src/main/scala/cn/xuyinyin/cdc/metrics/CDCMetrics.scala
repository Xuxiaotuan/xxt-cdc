package cn.xuyinyin.cdc.metrics

import cn.xuyinyin.cdc.model.BinlogPosition

import java.time.Instant
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration._

/**
 * CDC 指标收集器
 * 提供系统运行时的各种指标
 */
class CDCMetrics {
  
  // ========== 吞吐量指标 ==========
  private val ingestCount = new AtomicLong(0)
  private val applyCount = new AtomicLong(0)
  private val errorCount = new AtomicLong(0)
  
  // ========== 延迟指标 ==========
  private val currentBinlogPosition = new AtomicReference[Option[BinlogPosition]](None)
  private val currentBinlogTimestamp = new AtomicReference[Option[Instant]](None)
  private val lastCommittedPosition = new AtomicReference[Option[BinlogPosition]](None)
  private val lastCommitTime = new AtomicReference[Option[Instant]](None)
  
  // ========== 队列深度指标 ==========
  private val queueDepth = new AtomicLong(0)
  private val maxQueueDepth = new AtomicLong(0)
  
  // ========== 启动时间 ==========
  private val startTime = Instant.now()
  
  /**
   * 记录接收到的事件
   */
  def recordIngest(position: BinlogPosition, timestamp: Instant): Unit = {
    ingestCount.incrementAndGet()
    currentBinlogPosition.set(Some(position))
    currentBinlogTimestamp.set(Some(timestamp))
  }
  
  /**
   * 记录应用的事件
   */
  def recordApply(count: Int): Unit = {
    applyCount.addAndGet(count)
  }
  
  /**
   * 记录错误
   */
  def recordError(): Unit = {
    errorCount.incrementAndGet()
  }
  
  /**
   * 记录偏移量提交
   */
  def recordCommit(position: BinlogPosition): Unit = {
    lastCommittedPosition.set(Some(position))
    lastCommitTime.set(Some(Instant.now()))
  }
  
  /**
   * 更新队列深度
   */
  def updateQueueDepth(depth: Long): Unit = {
    queueDepth.set(depth)
    val current = maxQueueDepth.get()
    if (depth > current) {
      maxQueueDepth.compareAndSet(current, depth)
    }
  }
  
  /**
   * 计算 binlog lag（时间差）
   */
  def getBinlogLag(): Option[Duration] = {
    currentBinlogTimestamp.get().map { binlogTime =>
      val now = Instant.now()
      Duration.fromNanos(java.time.Duration.between(binlogTime, now).toNanos)
    }
  }
  
  /**
   * 计算 ingest TPS（每秒接收事件数）
   */
  def getIngestTPS(): Double = {
    val elapsed = java.time.Duration.between(startTime, Instant.now()).getSeconds
    if (elapsed > 0) {
      ingestCount.get().toDouble / elapsed
    } else {
      0.0
    }
  }
  
  /**
   * 计算 apply TPS（每秒应用事件数）
   */
  def getApplyTPS(): Double = {
    val elapsed = java.time.Duration.between(startTime, Instant.now()).getSeconds
    if (elapsed > 0) {
      applyCount.get().toDouble / elapsed
    } else {
      0.0
    }
  }
  
  /**
   * 计算错误率
   */
  def getErrorRate(): Double = {
    val total = ingestCount.get()
    if (total > 0) {
      errorCount.get().toDouble / total
    } else {
      0.0
    }
  }
  
  /**
   * 获取所有指标的快照
   */
  def getSnapshot(): MetricsSnapshot = {
    MetricsSnapshot(
      ingestCount = ingestCount.get(),
      applyCount = applyCount.get(),
      errorCount = errorCount.get(),
      ingestTPS = getIngestTPS(),
      applyTPS = getApplyTPS(),
      errorRate = getErrorRate(),
      binlogLag = getBinlogLag(),
      currentBinlogPosition = currentBinlogPosition.get(),
      lastCommittedPosition = lastCommittedPosition.get(),
      lastCommitTime = lastCommitTime.get(),
      queueDepth = queueDepth.get(),
      maxQueueDepth = maxQueueDepth.get(),
      uptime = Duration.fromNanos(java.time.Duration.between(startTime, Instant.now()).toNanos)
    )
  }
  
  /**
   * 重置指标
   */
  def reset(): Unit = {
    ingestCount.set(0)
    applyCount.set(0)
    errorCount.set(0)
    queueDepth.set(0)
    maxQueueDepth.set(0)
  }
}

/**
 * 指标快照
 */
case class MetricsSnapshot(
  ingestCount: Long,
  applyCount: Long,
  errorCount: Long,
  ingestTPS: Double,
  applyTPS: Double,
  errorRate: Double,
  binlogLag: Option[Duration],
  currentBinlogPosition: Option[BinlogPosition],
  lastCommittedPosition: Option[BinlogPosition],
  lastCommitTime: Option[Instant],
  queueDepth: Long,
  maxQueueDepth: Long,
  uptime: Duration
) {
  
  /**
   * 转换为 Map 格式（便于序列化）
   */
  def toMap: Map[String, Any] = Map(
    "ingestCount" -> ingestCount,
    "applyCount" -> applyCount,
    "errorCount" -> errorCount,
    "ingestTPS" -> f"$ingestTPS%.2f",
    "applyTPS" -> f"$applyTPS%.2f",
    "errorRate" -> f"${errorRate * 100}%.2f%%",
    "binlogLagSeconds" -> binlogLag.map(_.toSeconds).getOrElse(0L),
    "currentBinlogPosition" -> currentBinlogPosition.map(_.asString).getOrElse("N/A"),
    "lastCommittedPosition" -> lastCommittedPosition.map(_.asString).getOrElse("N/A"),
    "lastCommitTime" -> lastCommitTime.map(_.toString).getOrElse("N/A"),
    "queueDepth" -> queueDepth,
    "maxQueueDepth" -> maxQueueDepth,
    "uptimeSeconds" -> uptime.toSeconds
  )
  
  /**
   * 转换为 JSON 字符串
   */
  def toJson: String = {
    import spray.json._
    
    val jsonMap: Map[String, JsValue] = toMap.map {
      case (k, v: String) => k -> JsString(v)
      case (k, v: Long) => k -> JsNumber(v)
      case (k, v: Double) => k -> JsNumber(v)
      case (k, v: Int) => k -> JsNumber(v)
      case (k, v) => k -> JsString(v.toString)
    }
    JsObject(jsonMap).prettyPrint
  }
}

object CDCMetrics {
  /**
   * 创建 CDC Metrics 实例
   */
  def apply(): CDCMetrics = new CDCMetrics()
}
