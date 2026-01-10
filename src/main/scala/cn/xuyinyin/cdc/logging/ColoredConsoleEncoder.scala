package cn.xuyinyin.cdc.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.encoder.EncoderBase

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 彩色控制台日志 Encoder
 * 
 * 提供人类可读的文本格式日志输出，使用 ANSI 颜色码区分不同日志级别：
 * - ERROR: 红色
 * - WARN: 黄色
 * - INFO: 绿色
 * - DEBUG: 灰色
 * 
 * 日志格式：[时间戳] [级别] [阶段] [组件] - 消息内容
 */
class ColoredConsoleEncoder extends EncoderBase[ILoggingEvent] {
  
  private val dateFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())
  
  override def headerBytes(): Array[Byte] = Array.empty
  
  override def encode(event: ILoggingEvent): Array[Byte] = {
    val output = formatEvent(event)
    output.getBytes(StandardCharsets.UTF_8)
  }
  
  override def footerBytes(): Array[Byte] = Array.empty
  
  private def formatEvent(event: ILoggingEvent): String = {
    val timestamp = dateFormatter.format(Instant.ofEpochMilli(event.getTimeStamp))
    val level = event.getLevel
    val levelColor = getLevelColor(level)
    val levelStr = s"$levelColor[${level.toString.padTo(5, ' ')}]${LogColors.RESET}"
    
    // 从 MDC 获取阶段信息
    val phase = Option(event.getMDCPropertyMap.get("phase"))
      .map(p => s"${LogColors.CYAN}[$p]${LogColors.RESET} ")
      .getOrElse("")
    
    // 获取线程名称
    val threadName = event.getThreadName
    val threadStr = s"[${LogColors.GRAY}$threadName${LogColors.RESET}]"
    
    // 获取组件名称（Logger 名称的最后一部分）
    val component = event.getLoggerName.split('.').lastOption.getOrElse(event.getLoggerName)
    val componentStr = s"[$component]"
    
    // 消息内容
    val message = event.getFormattedMessage
    
    // 堆栈跟踪（如果有）
    val throwable = Option(event.getThrowableProxy).map { proxy =>
      val sb = new StringBuilder
      sb.append("\n")
      sb.append(s"${LogColors.RED}${proxy.getClassName}: ${proxy.getMessage}${LogColors.RESET}\n")
      
      // 只显示前 10 行堆栈跟踪
      proxy.getStackTraceElementProxyArray.take(10).foreach { ste =>
        sb.append(s"${LogColors.GRAY}  at ${ste.getSTEAsString}${LogColors.RESET}\n")
      }
      
      if (proxy.getStackTraceElementProxyArray.length > 10) {
        sb.append(s"${LogColors.GRAY}  ... ${proxy.getStackTraceElementProxyArray.length - 10} more${LogColors.RESET}\n")
      }
      
      sb.toString()
    }.getOrElse("")
    
    s"[$timestamp] $levelStr $phase$threadStr $componentStr - $message$throwable\n"
  }
  
  private def getLevelColor(level: Level): String = {
    level.toInt match {
      case Level.ERROR_INT => LogColors.RED
      case Level.WARN_INT => LogColors.YELLOW
      case Level.INFO_INT => LogColors.GREEN
      case Level.DEBUG_INT => LogColors.GRAY
      case Level.TRACE_INT => LogColors.GRAY
      case _ => LogColors.RESET
    }
  }
}

/**
 * ANSI 颜色码常量
 */
object LogColors {
  val RESET = "\u001B[0m"
  val RED = "\u001B[31m"
  val YELLOW = "\u001B[33m"
  val GREEN = "\u001B[32m"
  val CYAN = "\u001B[36m"
  val GRAY = "\u001B[90m"
  val BOLD = "\u001B[1m"
}
