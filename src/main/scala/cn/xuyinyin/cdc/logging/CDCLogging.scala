package cn.xuyinyin.cdc.logging

import cn.xuyinyin.cdc.model.{BinlogPosition, CDCState, TableId}
import com.typesafe.scalalogging.LazyLogging
import org.slf4j.MDC

/**
 * CDC 日志上下文管理 Trait
 * 
 * 提供 MDC (Mapped Diagnostic Context) 上下文管理功能，
 * 用于在日志中自动包含阶段、表名、位置等上下文信息。
 * 
 * 使用方式：
 * {{{
 * class MyComponent extends CDCLogging {
 *   def process(): Unit = {
 *     withPhase(Streaming) {
 *       withTable(TableId("test", "users")) {
 *         logger.info("Processing table")  // 日志会自动包含阶段和表名
 *       }
 *     }
 *   }
 * }
 * }}}
 */
trait CDCLogging extends LazyLogging {
  
  /**
   * 在指定的 CDC 阶段上下文中执行代码块
   * 
   * @param phase CDC 阶段（INIT/SNAPSHOT/CATCHUP/STREAMING）
   * @param block 要执行的代码块
   * @tparam T 返回值类型
   * @return 代码块的返回值
   */
  def withPhase[T](phase: CDCState)(block: => T): T = {
    MDC.put("phase", phase.name)
    try {
      block
    } finally {
      MDC.remove("phase")
    }
  }
  
  /**
   * 在指定的表上下文中执行代码块
   * 
   * @param tableId 表标识
   * @param block 要执行的代码块
   * @tparam T 返回值类型
   * @return 代码块的返回值
   */
  def withTable[T](tableId: TableId)(block: => T): T = {
    MDC.put("table", tableId.toString)
    try {
      block
    } finally {
      MDC.remove("table")
    }
  }
  
  /**
   * 在指定的 binlog 位置上下文中执行代码块
   * 
   * @param position binlog 位置
   * @param block 要执行的代码块
   * @tparam T 返回值类型
   * @return 代码块的返回值
   */
  def withPosition[T](position: BinlogPosition)(block: => T): T = {
    MDC.put("position", position.asString)
    try {
      block
    } finally {
      MDC.remove("position")
    }
  }
  
  /**
   * 在指定的分区上下文中执行代码块
   * 
   * @param partition 分区号
   * @param block 要执行的代码块
   * @tparam T 返回值类型
   * @return 代码块的返回值
   */
  def withPartition[T](partition: Int)(block: => T): T = {
    MDC.put("partition", partition.toString)
    try {
      block
    } finally {
      MDC.remove("partition")
    }
  }
  
  /**
   * 在多个上下文中执行代码块
   * 
   * @param contexts 上下文键值对
   * @param block 要执行的代码块
   * @tparam T 返回值类型
   * @return 代码块的返回值
   */
  def withContext[T](contexts: (String, String)*)(block: => T): T = {
    contexts.foreach { case (key, value) => MDC.put(key, value) }
    try {
      block
    } finally {
      contexts.foreach { case (key, _) => MDC.remove(key) }
    }
  }
  
  /**
   * 记录带有完整上下文的错误日志
   * 
   * @param message 错误消息
   * @param tableId 表标识（可选）
   * @param position binlog 位置（可选）
   * @param operation 操作类型（可选）
   * @param ex 异常（可选）
   */
  def logError(
    message: String,
    tableId: Option[TableId] = None,
    position: Option[BinlogPosition] = None,
    operation: Option[String] = None,
    ex: Option[Throwable] = None
  ): Unit = {
    val contextInfo = Seq(
      tableId.map(t => s"table=${t}"),
      position.map(p => s"position=${p.asString}"),
      operation.map(op => s"operation=$op")
    ).flatten.mkString(", ")
    
    val fullMessage = if (contextInfo.nonEmpty) {
      s"$message [$contextInfo]"
    } else {
      message
    }
    
    ex match {
      case Some(throwable) => logger.error(fullMessage, throwable)
      case None => logger.error(fullMessage)
    }
  }
  
  /**
   * 记录带有完整上下文的警告日志
   * 
   * @param message 警告消息
   * @param tableId 表标识（可选）
   * @param position binlog 位置（可选）
   */
  def logWarn(
    message: String,
    tableId: Option[TableId] = None,
    position: Option[BinlogPosition] = None
  ): Unit = {
    val contextInfo = Seq(
      tableId.map(t => s"table=${t}"),
      position.map(p => s"position=${p.asString}")
    ).flatten.mkString(", ")
    
    val fullMessage = if (contextInfo.nonEmpty) {
      s"$message [$contextInfo]"
    } else {
      message
    }
    
    logger.warn(fullMessage)
  }
  
  /**
   * 记录带有加粗效果的重要信息日志
   * 
   * @param message 消息内容
   */
  def logBold(message: String): Unit = {
    logger.info(s"${LogColors.BOLD}$message${LogColors.RESET}")
  }
}
