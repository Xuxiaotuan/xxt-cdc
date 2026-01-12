package cn.xuyinyin.cdc.engine

import cn.xuyinyin.cdc.config.CDCConfig
import cn.xuyinyin.cdc.reader.MySQLBinlogReader
import cn.xuyinyin.cdc.logging.CDCLogging
import cn.xuyinyin.cdc.model._
import cn.xuyinyin.cdc.normalizer.EventNormalizer
import cn.xuyinyin.cdc.router.EventRouter
import cn.xuyinyin.cdc.worker.ApplyWorker
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Sink}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * CDC 引擎工具类
 * 
 * 包含 CDCEngine 使用的辅助方法，保持 CDCEngine 的简洁性
 */
object CDCEngineUtils extends CDCLogging {
  
  /**
   * 获取最新的 binlog 位置
   * 通过查询 MySQL 的 SHOW BINARY LOG STATUS 或 SHOW MASTER STATUS 获取当前最新的 binlog 文件和位置
   * 支持 MySQL 8.2+ 的新语法和旧版本的兼容性
   */
  def getLatestBinlogPosition(config: CDCConfig): FilePosition = {
    import java.sql.{DriverManager, SQLException}
    
    val url = s"jdbc:mysql://${config.source.host}:${config.source.port}/${config.source.database}?useSSL=false&allowPublicKeyRetrieval=true"
    
    var conn: java.sql.Connection = null
    try {
      conn = DriverManager.getConnection(url, config.source.username, config.source.password)
      
      // 尝试新语法（MySQL 8.2+）
      val position = try {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SHOW BINARY LOG STATUS")
        
        if (rs.next()) {
          val filename = rs.getString("File")
          val pos = rs.getLong("Position")
          logger.info(s"Latest binlog position (BINARY LOG STATUS): $filename:$pos")
          Some(FilePosition(filename, pos))
        } else {
          None
        }
      } catch {
        case _: SQLException =>
          // 新语法失败，尝试旧语法
          logger.debug("SHOW BINARY LOG STATUS not supported, trying SHOW MASTER STATUS")
          None
      }
      
      // 如果新语法失败，尝试旧语法（MySQL 5.x - 8.1）
      position.getOrElse {
        try {
          val stmt = conn.createStatement()
          val rs = stmt.executeQuery("SHOW MASTER STATUS")
          
          if (rs.next()) {
            val filename = rs.getString("File")
            val pos = rs.getLong("Position")
            logger.info(s"Latest binlog position (MASTER STATUS): $filename:$pos")
            FilePosition(filename, pos)
          } else {
            logger.warn("No binlog status found, falling back to default position")
            FilePosition("mysql-bin.000001", 4L)
          }
        } catch {
          case ex: SQLException =>
            logger.error(s"Failed to get binlog position: ${ex.getMessage}. Check if binlog is enabled and user has REPLICATION CLIENT privilege.")
            logger.warn("Falling back to default position: mysql-bin.000001:4")
            FilePosition("mysql-bin.000001", 4L)
        }
      }
      
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to connect to MySQL: ${ex.getMessage}", ex)
        logger.warn("Falling back to default position: mysql-bin.000001:4")
        FilePosition("mysql-bin.000001", 4L)
    } finally {
      if (conn != null) {
        try {
          conn.close()
        } catch {
          case ex: Exception =>
            logger.warn(s"Failed to close connection: ${ex.getMessage}")
        }
      }
    }
  }
  
  /**
   * 为单个表执行快照
   * 
   * @param tableId 表标识
   * @param config CDC 配置
   * @return Future[Long] 复制的行数
   */
  def performTableSnapshot(tableId: TableId, config: CDCConfig)(implicit ec: ExecutionContext): Future[Long] = Future {
    import java.sql.DriverManager
    
    logger.info(s"Starting snapshot for table ${tableId.database}.${tableId.table}")
    
    val sourceUrl = s"jdbc:mysql://${config.source.host}:${config.source.port}/${tableId.database}?useSSL=false&allowPublicKeyRetrieval=true"
    val targetUrl = s"jdbc:mysql://${config.target.host}:${config.target.port}/${config.target.database}?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    
    var sourceConn: java.sql.Connection = null
    var targetConn: java.sql.Connection = null
    var rowCount = 0L
    
    try {
      // 连接源数据库
      sourceConn = DriverManager.getConnection(sourceUrl, config.source.username, config.source.password)
      sourceConn.setAutoCommit(false)
      sourceConn.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
      
      // 连接目标数据库
      targetConn = DriverManager.getConnection(targetUrl, config.target.username, config.target.password)
      targetConn.setAutoCommit(false)
      
      // 查询源表数据
      val selectSql = s"SELECT * FROM `${tableId.table}`"
      val selectStmt = sourceConn.createStatement()
      selectStmt.setFetchSize(1000) // 流式读取
      val rs = selectStmt.executeQuery(selectSql)
      
      // 获取列信息
      val metaData = rs.getMetaData
      val columnCount = metaData.getColumnCount
      val columnNames = (1 to columnCount).map(metaData.getColumnName).mkString(", ")
      val placeholders = (1 to columnCount).map(_ => "?").mkString(", ")
      
      // 准备插入语句（使用 REPLACE INTO 实现幂等）
      val insertSql = s"REPLACE INTO `${tableId.table}` ($columnNames) VALUES ($placeholders)"
      val insertStmt = targetConn.prepareStatement(insertSql)
      
      // 批量插入
      val batchSize = config.parallelism.batchSize
      var batchCount = 0
      
      while (rs.next()) {
        // 设置参数
        for (i <- 1 to columnCount) {
          insertStmt.setObject(i, rs.getObject(i))
        }
        insertStmt.addBatch()
        batchCount += 1
        rowCount += 1
        
        // 执行批量插入
        if (batchCount >= batchSize) {
          insertStmt.executeBatch()
          targetConn.commit()
          batchCount = 0
          
          if (rowCount % 10000 == 0) {
            logger.info(s"Snapshot progress for ${tableId.table}: $rowCount rows")
          }
        }
      }
      
      // 执行剩余的批量
      if (batchCount > 0) {
        insertStmt.executeBatch()
        targetConn.commit()
      }
      
      logger.info(s"Snapshot completed for table ${tableId.database}.${tableId.table}: $rowCount rows")
      rowCount
      
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to snapshot table ${tableId.database}.${tableId.table}: ${ex.getMessage}", ex)
        if (targetConn != null) {
          try {
            targetConn.rollback()
          } catch {
            case _: Exception => // 忽略回滚错误
          }
        }
        throw ex
    } finally {
      if (sourceConn != null) try { sourceConn.close() } catch { case _: Exception => }
      if (targetConn != null) try { targetConn.close() } catch { case _: Exception => }
    }
  }
  
  /**
   * 执行指定范围的 Catchup 处理
   * 
   * @param lowWatermark 起始位置（快照开始时的 binlog 位置）
   * @param highWatermark 结束位置（快照结束时的 binlog 位置）
   * @param snapshotTables 快照表列表
   * @param config CDC 配置
   * @param eventNormalizer 事件标准化器
   * @param eventRouter 事件路由器
   * @param applyWorkers 应用工作器列表
   * @return Future[Unit] Catchup 完成的 Future
   */
  def performCatchupRange(
    lowWatermark: BinlogPosition,
    highWatermark: BinlogPosition,
    snapshotTables: Set[TableId],
    config: CDCConfig,
    eventNormalizer: EventNormalizer,
    eventRouter: EventRouter,
    applyWorkers: Seq[ApplyWorker]
  )(implicit mat: Materializer, ec: ExecutionContext): Future[Unit] = {
    
    logger.info(s"Creating catchup binlog reader from position: ${lowWatermark.asString}")
    
    // 创建临时的 BinlogReader 用于 catchup
    val catchupReader = MySQLBinlogReader(config.source)
    
    // 记录开始时间，用于性能统计
    val startTime = System.currentTimeMillis()
    var lastProgressTime = startTime
    var processedEvents = 0L
    
    // 创建事件处理流
    val catchupFlow = Flow[cn.xuyinyin.cdc.reader.RawBinlogEvent]
      .mapConcat { rawEvent =>
        // 标准化事件
        eventNormalizer.normalize(rawEvent).toList
      }
      .filter { event =>
        // 只处理快照表的事件
        val isTargetTable = snapshotTables.contains(event.tableId)
        if (!isTargetTable) {
          logger.debug(s"Skipping non-snapshot table event: ${event.tableId}")
        }
        isTargetTable
      }
      .takeWhile { event =>
        // 当事件位置达到或超过 high watermark 时停止
        val shouldContinue = event.position.compare(highWatermark) < 0
        if (!shouldContinue) {
          logger.info(s"Reached high watermark at position: ${event.position.asString}")
        }
        shouldContinue
      }
      .map { event =>
        // 路由事件到分区
        val partition = eventRouter.route(event)
        RoutedEvent(event, partition)
      }
      .mapAsync(config.parallelism.applyWorkerCount) { routedEvent =>
        // 应用事件到目标数据库
        val worker = applyWorkers(routedEvent.partition % applyWorkers.length)
        worker.apply(Seq(routedEvent.event)).recover {
          case ex: Exception =>
            logger.error(s"Failed to apply catchup event: ${ex.getMessage}", ex)
            // 继续处理其他事件，不中断整个 catchup 流程
            ()
        }.map { _ =>
          // 更新进度统计
          processedEvents += 1
          val currentTime = System.currentTimeMillis()
          
          // 每 1000 个事件或每 30 秒报告一次进度
          if (processedEvents % 1000 == 0 || (currentTime - lastProgressTime) > 30000) {
            val elapsed = (currentTime - startTime) / 1000.0
            val rate = if (elapsed > 0) processedEvents / elapsed else 0.0
            logger.info(f"Catchup progress: $processedEvents events processed, rate: $rate%.1f events/s, current position: ${routedEvent.event.position.asString}")
            lastProgressTime = currentTime
          }
          
          routedEvent
        }
      }
    
    // 执行 catchup 流程
    val catchupFuture = catchupReader.start(lowWatermark)
      .via(catchupFlow)
      .runWith(Sink.fold((0L, lowWatermark)) { case ((count, _), routedEvent) =>
        (count + 1, routedEvent.event.position)
      })
    
    // 处理完成后的清理
    catchupFuture.andThen {
      case Success((eventCount, finalPosition)) =>
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if (elapsed > 0) eventCount / elapsed else 0.0
        logger.info(f"Catchup phase completed successfully. Processed $eventCount events in $elapsed%.1fs ($rate%.1f events/s)")
        logger.info(s"Final catchup position: ${finalPosition.asString}")
        catchupReader.stop()
        
      case Failure(exception) =>
        logger.error(s"Catchup phase failed: ${exception.getMessage}", exception)
        catchupReader.stop()
    }.map(_ => ())
  }
}
