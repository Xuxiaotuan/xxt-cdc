package cn.xuyinyin.cdc.error

import com.typesafe.scalalogging.LazyLogging

import java.sql.{SQLException, SQLTransientException}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.Random

/**
 * CDC 错误分类
 */
sealed trait CDCError {
  def cause: Throwable
  def message: String
  def canRetry: Boolean
}

/**
 * 可重试错误
 * 通常是临时性的网络、连接或资源问题
 */
case class RetryableError(
  cause: Throwable,
  message: String,
  retryCount: Int = 0,
  maxRetries: Int = 3
) extends CDCError {
  override def canRetry: Boolean = retryCount < maxRetries
  
  def incrementRetry(): RetryableError = copy(retryCount = retryCount + 1)
}

/**
 * 不可重试错误
 * 通常是配置错误、权限问题或数据格式错误
 */
case class NonRetryableError(
  cause: Throwable,
  message: String,
  reason: String
) extends CDCError {
  override def canRetry: Boolean = false
}

/**
 * 致命错误
 * 需要重启系统或人工干预
 */
case class FatalError(
  cause: Throwable,
  message: String,
  requiresRestart: Boolean = false
) extends CDCError {
  override def canRetry: Boolean = false
}

/**
 * 错误处理动作
 */
sealed trait ErrorAction
case object Retry extends ErrorAction
case object Skip extends ErrorAction
case object Fail extends ErrorAction
case object Restart extends ErrorAction

/**
 * 错误上下文
 */
case class ErrorContext(
  component: String,
  operation: String,
  metadata: Map[String, Any] = Map.empty
)

/**
 * 错误处理器
 * 负责错误分类和处理策略决策
 */
class ErrorHandler extends LazyLogging {
  
  private val errorCounts = scala.collection.concurrent.TrieMap[String, AtomicInteger]()
  
  /**
   * 分类错误类型
   */
  def classify(error: Throwable, context: ErrorContext): CDCError = {
    error match {
      // 数据库连接相关错误
      case ex: SQLException =>
        classifySQLException(ex, context)
        
      // 网络相关错误
      case ex: java.net.ConnectException =>
        RetryableError(ex, s"Connection failed: ${ex.getMessage}")
        
      case ex: java.net.SocketTimeoutException =>
        RetryableError(ex, s"Socket timeout: ${ex.getMessage}")
        
      case ex: java.io.IOException =>
        RetryableError(ex, s"IO error: ${ex.getMessage}")
        
      // 配置相关错误
      case ex: IllegalArgumentException =>
        NonRetryableError(ex, s"Invalid configuration: ${ex.getMessage}", "configuration")
        
      case ex: IllegalStateException =>
        NonRetryableError(ex, s"Invalid state: ${ex.getMessage}", "state")
        
      // 内存相关错误
      case ex: OutOfMemoryError =>
        FatalError(ex, "Out of memory", requiresRestart = true)
        
      // 其他运行时异常
      case ex: RuntimeException =>
        classifyRuntimeException(ex, context)
        
      // 默认处理
      case ex =>
        logger.warn(s"Unknown error type: ${ex.getClass.getSimpleName}")
        RetryableError(ex, s"Unknown error: ${ex.getMessage}")
    }
  }
  
  private def classifySQLException(ex: SQLException, context: ErrorContext): CDCError = {
    val errorCode = ex.getErrorCode

    errorCode match {
      // MySQL 连接相关错误
      case 1040 => // Too many connections
        RetryableError(ex, "Too many connections to MySQL")
        
      case 1045 => // Access denied
        NonRetryableError(ex, "Access denied to MySQL", "authentication")
        
      case 1049 => // Unknown database
        NonRetryableError(ex, "Unknown database", "configuration")
        
      case 1146 => // Table doesn't exist
        NonRetryableError(ex, "Table doesn't exist", "schema")
        
      case 1062 => // Duplicate entry
        // 在幂等写入中，重复键是正常的
        if (context.operation == "insert") {
          logger.debug("Duplicate key on insert (expected in idempotent operations)")
          NonRetryableError(ex, "Duplicate key (handled)", "duplicate")
        } else {
          NonRetryableError(ex, "Duplicate key error", "data")
        }
        
      case 2006 => // MySQL server has gone away
        RetryableError(ex, "MySQL server has gone away")
        
      case 2013 => // Lost connection to MySQL server
        RetryableError(ex, "Lost connection to MySQL server")
        
      case _ =>
        // 检查是否是临时性错误
        if (ex.isInstanceOf[SQLTransientException]) {
          RetryableError(ex, s"Transient SQL error: ${ex.getMessage}")
        } else {
          NonRetryableError(ex, s"SQL error: ${ex.getMessage}", "sql")
        }
    }
  }
  
  private def classifyRuntimeException(ex: RuntimeException, context: ErrorContext): CDCError = {
    ex.getMessage match {
      case msg if msg != null && msg.contains("timeout") =>
        RetryableError(ex, s"Timeout error: $msg")
        
      case msg if msg != null && msg.contains("connection") =>
        RetryableError(ex, s"Connection error: $msg")
        
      case _ =>
        NonRetryableError(ex, s"Runtime error: ${ex.getMessage}", "runtime")
    }
  }
  
  /**
   * 决定错误处理动作
   */
  def handle(error: CDCError, context: ErrorContext): ErrorAction = {
    // 记录错误统计
    recordError(error, context)
    
    error match {
      case retryable: RetryableError if retryable.canRetry =>
        logger.warn(s"Retryable error in ${context.component}.${context.operation} " +
          s"(attempt ${retryable.retryCount + 1}/${retryable.maxRetries}): ${retryable.message}")
        Retry
        
      case _: RetryableError =>
        logger.error(s"Max retries exceeded in ${context.component}.${context.operation}")
        Fail
        
      case nonRetryable: NonRetryableError =>
        logger.error(s"Non-retryable error in ${context.component}.${context.operation}: ${nonRetryable.message}")
        nonRetryable.reason match {
          case "duplicate" => Skip // 重复键错误可以跳过
          case _ => Fail
        }
        
      case fatal: FatalError =>
        logger.error(s"Fatal error in ${context.component}.${context.operation}: ${fatal.message}")
        if (fatal.requiresRestart) {
          Restart
        } else {
          Fail
        }
    }
  }
  
  private def recordError(error: CDCError, context: ErrorContext): Unit = {
    val key = s"${context.component}.${context.operation}.${error.getClass.getSimpleName}"
    errorCounts.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }
  
  /**
   * 获取错误统计
   */
  def getErrorStatistics(): Map[String, Int] = {
    errorCounts.map { case (key, count) => key -> count.get() }.toMap
  }
  
  /**
   * 重置错误统计
   */
  def resetErrorStatistics(): Unit = {
    errorCounts.clear()
  }
}

/**
 * 重试策略
 */
class RetryStrategy extends LazyLogging {
  
  /**
   * 执行带重试的操作
   */
  def executeWithRetry[T](
    operation: () => Future[T],
    context: ErrorContext,
    errorHandler: ErrorHandler,
    maxRetries: Int = 3
  )(implicit ec: ExecutionContext): Future[T] = {
    
    def attempt(retryCount: Int): Future[T] = {
      operation().recoverWith { case ex =>
        val error = errorHandler.classify(ex, context) match {
          case retryable: RetryableError =>
            retryable.copy(retryCount = retryCount, maxRetries = maxRetries)
          case other => other
        }
        
        errorHandler.handle(error, context) match {
          case Retry if retryCount < maxRetries =>
            val delay = calculateBackoffDelay(retryCount)
            logger.info(s"Retrying ${context.operation} after ${delay.toMillis}ms (attempt ${retryCount + 1}/$maxRetries)")
            
            Future {
              Thread.sleep(delay.toMillis)
            }.flatMap(_ => attempt(retryCount + 1))
            
          case Skip =>
            logger.info(s"Skipping failed operation: ${context.operation}")
            Future.successful(null.asInstanceOf[T]) // 返回默认值
            
          case _ =>
            Future.failed(ex)
        }
      }
    }
    
    attempt(0)
  }
  
  /**
   * 计算指数退避延迟
   */
  private def calculateBackoffDelay(retryCount: Int): FiniteDuration = {
    val baseDelay = 1.second
    val maxDelay = 30.seconds
    val jitter = Random.nextDouble() * 0.1 // 10% 抖动
    
    val delay = baseDelay * math.pow(2, retryCount) * (1 + jitter)
    FiniteDuration(math.min(delay.toMillis, maxDelay.toMillis), MILLISECONDS)
  }
}

/**
 * 断路器模式实现
 */
class CircuitBreaker(
  failureThreshold: Int = 5,
  timeout: FiniteDuration = 60.seconds
) extends LazyLogging {
  
  private sealed trait State
  private case object Closed extends State
  private case object Open extends State
  private case object HalfOpen extends State
  
  @volatile private var state: State = Closed
  @volatile private var failureCount: Int = 0
  @volatile private var lastFailureTime: Long = 0
  
  /**
   * 执行操作，如果断路器打开则快速失败
   */
  def execute[T](operation: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    state match {
      case Closed =>
        executeAndRecordResult(operation)
        
      case Open =>
        if (System.currentTimeMillis() - lastFailureTime > timeout.toMillis) {
          logger.info("Circuit breaker transitioning to half-open")
          state = HalfOpen
          executeAndRecordResult(operation)
        } else {
          Future.failed(new RuntimeException("Circuit breaker is open"))
        }
        
      case HalfOpen =>
        executeAndRecordResult(operation)
    }
  }
  
  private def executeAndRecordResult[T](operation: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    operation().andThen {
      case Success(_) =>
        onSuccess()
      case Failure(_) =>
        onFailure()
    }
  }
  
  private def onSuccess(): Unit = {
    failureCount = 0
    if (state == HalfOpen) {
      logger.info("Circuit breaker closing")
      state = Closed
    }
  }
  
  private def onFailure(): Unit = {
    failureCount += 1
    lastFailureTime = System.currentTimeMillis()
    
    if (state == Closed && failureCount >= failureThreshold) {
      logger.warn(s"Circuit breaker opening after $failureCount failures")
      state = Open
    } else if (state == HalfOpen) {
      logger.warn("Circuit breaker opening again")
      state = Open
    }
  }
  
  /**
   * 获取断路器状态
   */
  def getState(): String = state.toString
  
  /**
   * 获取失败计数
   */
  def getFailureCount(): Int = failureCount
}

object ErrorHandler {
  /**
   * 创建默认的错误处理器
   */
  def apply(): ErrorHandler = new ErrorHandler()
}

object RetryStrategy {
  /**
   * 创建默认的重试策略
   */
  def apply(): RetryStrategy = new RetryStrategy()
}

object CircuitBreaker {
  /**
   * 创建断路器
   */
  def apply(failureThreshold: Int = 5, timeout: FiniteDuration = 60.seconds): CircuitBreaker = {
    new CircuitBreaker(failureThreshold, timeout)
  }
}