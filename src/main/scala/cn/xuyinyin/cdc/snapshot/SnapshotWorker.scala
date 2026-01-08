package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent, Insert, TableId}
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}

/**
 * 快照结果
 */
case class SnapshotResult(
  taskId: String,
  tableId: TableId,
  rowCount: Long,
  startTime: Instant,
  endTime: Instant,
  chunks: Int
) {
  def duration: Long = endTime.toEpochMilli - startTime.toEpochMilli
}

/**
 * 快照工作器消息
 */
sealed trait SnapshotWorkerMessage

case class StartSnapshot(
  task: SnapshotTask,
  onComplete: ActorRef[SnapshotResult],
  onError: ActorRef[Throwable]
) extends SnapshotWorkerMessage

case class CancelSnapshotTask(taskId: String) extends SnapshotWorkerMessage

private case class ProcessChunk(
  chunkId: Int,
  startKey: Option[Any],
  endKey: Option[Any]
) extends SnapshotWorkerMessage

private case class ChunkCompleted(chunkId: Int, rowCount: Long) extends SnapshotWorkerMessage
private case class ChunkFailed(chunkId: Int, error: Throwable) extends SnapshotWorkerMessage

/**
 * 快照分片信息
 */
case class SnapshotChunk(
  id: Int,
  startKey: Option[Any],
  endKey: Option[Any],
  rowCount: Long = 0,
  completed: Boolean = false
)

/**
 * 快照工作器
 * 负责执行单个表的快照任务
 */
object SnapshotWorker extends LazyLogging {
  
  def apply(
    task: SnapshotTask,
    config: SnapshotSchedulerConfig
  ): Behavior[SnapshotWorkerMessage] = {
    Behaviors.setup { context =>
      implicit val ec: ExecutionContext = context.executionContext
      
      idle(task, config)
    }
  }
  
  /**
   * 创建一个空闲的工作器（用于工作器池）
   */
  def idle(
    catalogService: CatalogService,
    sink: cn.xuyinyin.cdc.sink.MySQLSink
  ): Behavior[SnapshotWorkerMessage] = {
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext
      
      message match {
        case StartSnapshot(task, onComplete, onError) =>
          logger.info(s"Starting snapshot for table ${task.tableId}")
          
          val startTime = Instant.now()
          val config = SnapshotSchedulerConfig() // 使用默认配置
          
          // 异步执行快照
          Future {
            executeSnapshot(task, config, catalogService, sink)
          }.onComplete {
            case Success(result) =>
              val endTime = Instant.now()
              val snapshotResult = SnapshotResult(
                taskId = task.id,
                tableId = task.tableId,
                rowCount = result,
                startTime = startTime,
                endTime = endTime,
                chunks = 1
              )
              onComplete ! snapshotResult
              
            case Failure(error) =>
              logger.error(s"Snapshot failed for table ${task.tableId}: ${error.getMessage}", error)
              onError ! error
          }
          
          // 返回空闲状态，准备接收下一个任务
          idle(catalogService, sink)
          
        case _ =>
          Behaviors.unhandled
      }
    }
  }
  
  private def idle(
    task: SnapshotTask,
    config: SnapshotSchedulerConfig
  ): Behavior[SnapshotWorkerMessage] = {
    
    Behaviors.receive { (context, message) =>
      implicit val ec: ExecutionContext = context.executionContext
      
      message match {
        case StartSnapshot(snapshotTask, onComplete, onError) =>
          logger.info(s"Starting snapshot for table ${snapshotTask.tableId}")
          
          val startTime = Instant.now()
          
          // 异步执行快照
          Future {
            executeSnapshot(snapshotTask, config, null, null)
          }.onComplete {
            case Success(result) =>
              val endTime = Instant.now()
              val snapshotResult = SnapshotResult(
                taskId = snapshotTask.id,
                tableId = snapshotTask.tableId,
                rowCount = result,
                startTime = startTime,
                endTime = endTime,
                chunks = 1 // 简化版本，后续可以支持多分片
              )
              onComplete ! snapshotResult
              
            case Failure(error) =>
              logger.error(s"Snapshot failed for table ${snapshotTask.tableId}: ${error.getMessage}", error)
              onError ! error
          }
          
          running(task, config, onComplete, onError)
          
        case _ =>
          Behaviors.unhandled
      }
    }
  }
  
  private def running(
    task: SnapshotTask,
    config: SnapshotSchedulerConfig,
    onComplete: ActorRef[SnapshotResult],
    onError: ActorRef[Throwable]
  ): Behavior[SnapshotWorkerMessage] = {
    
    Behaviors.receive { (context, message) =>
      message match {
        case CancelSnapshotTask(taskId) if taskId == task.id =>
          logger.info(s"Cancelling snapshot task $taskId")
          // 在实际实现中，这里应该中断正在进行的快照操作
          Behaviors.stopped
          
        case _ =>
          Behaviors.same
      }
    }
  }
  
  /**
   * 执行快照
   */
  private def executeSnapshot(
    task: SnapshotTask,
    config: SnapshotSchedulerConfig,
    catalogService: CatalogService,
    sink: cn.xuyinyin.cdc.sink.MySQLSink
  ): Long = {
    logger.info(s"Executing snapshot for table ${task.tableId}")
    
    // 暂时返回0，表示没有处理任何行
    // TODO: 需要实现完整的快照逻辑
    logger.warn(s"Snapshot execution not fully implemented for table ${task.tableId}")
    0L
  }
  
  /**
   * 执行快照查询（已废弃，需要重新实现）
   */
  /*
  private def executeSnapshotQuery(
    task: SnapshotTask,
    config: SnapshotSchedulerConfig
  ): Long = {
    // 实现已注释
    0L
  }
  */
}

/**
 * 快照分片器
 * 负责将大表分割成多个分片进行并行处理
 */
class SnapshotChunker(
  catalogService: CatalogService,
  maxRowsPerChunk: Long = 10000
) extends LazyLogging {
  
  /**
   * 为表生成分片
   */
  def generateChunks(tableId: TableId): Seq[SnapshotChunk] = {
    logger.info(s"Generating chunks for table $tableId")
    
    // 简化实现：使用单个分片
    // TODO: 实现基于主键的分片逻辑
    logger.warn(s"Using single chunk for table $tableId (chunking not yet implemented)")
    Seq(SnapshotChunk(0, None, None))
  }
  
  private def generateChunksByPrimaryKey(
    tableId: TableId,
    primaryKeyColumn: String,
    metadata: Any
  ): Seq[SnapshotChunk] = {
    
    // 获取主键范围
    val (minKey, maxKey, totalRows) = getPrimaryKeyRange(tableId, primaryKeyColumn)
    
    if (totalRows <= maxRowsPerChunk) {
      logger.info(s"Table $tableId has $totalRows rows, using single chunk")
      return Seq(SnapshotChunk(0, Some(minKey), Some(maxKey)))
    }
    
    val chunkCount = Math.ceil(totalRows.toDouble / maxRowsPerChunk).toInt
    logger.info(s"Table $tableId has $totalRows rows, splitting into $chunkCount chunks")
    
    // 计算分片边界
    val chunkSize = (maxKey.asInstanceOf[Long] - minKey.asInstanceOf[Long]) / chunkCount
    
    (0 until chunkCount).map { i =>
      val startKey = if (i == 0) minKey else minKey.asInstanceOf[Long] + (i * chunkSize)
      val endKey = if (i == chunkCount - 1) maxKey else minKey.asInstanceOf[Long] + ((i + 1) * chunkSize) - 1
      
      SnapshotChunk(i, Some(startKey), Some(endKey))
    }
  }
  
  private def getPrimaryKeyRange(tableId: TableId, primaryKeyColumn: String): (Any, Any, Long) = {
    val url = "jdbc:mysql://localhost:3306" // 从配置获取
    val username = "root"
    val password = "password"
    
    Using.Manager { use =>
      val connection = use(DriverManager.getConnection(url, username, password))
      
      val sql = s"""
        SELECT 
          MIN(`$primaryKeyColumn`) as min_key,
          MAX(`$primaryKeyColumn`) as max_key,
          COUNT(*) as total_rows
        FROM `${tableId.database}`.`${tableId.table}`
      """
      
      val stmt = use(connection.prepareStatement(sql))
      val rs = use(stmt.executeQuery())
      
      if (rs.next()) {
        val minKey = rs.getObject("min_key")
        val maxKey = rs.getObject("max_key")
        val totalRows = rs.getLong("total_rows")
        
        (minKey, maxKey, totalRows)
      } else {
        throw new RuntimeException(s"Failed to get primary key range for table $tableId")
      }
    }.get
  }
}