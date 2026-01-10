package cn.xuyinyin.cdc.reader

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.model.{BinlogPosition, FilePosition, GTIDPosition, TableId}
import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.github.shyiko.mysql.binlog.event._
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.util.ByteString

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * MySQL Binlog Reader 实现
 * 使用 mysql-binlog-connector-java 库读取 binlog 事件
 * 
 * @param config 数据库配置
 * @param bufferSize 缓冲区大小
 */
class MySQLBinlogReader(
  config: DatabaseConfig,
  bufferSize: Int = 1000
)(implicit mat: Materializer, ec: ExecutionContext) extends BinlogReader with LazyLogging {

  private val client = new BinaryLogClient(
    config.host,
    config.port,
    config.username,
    config.password
  )
  
  // 当前位置的原子引用
  private val currentPosition = new AtomicReference[BinlogPosition](
    FilePosition("", 0L)
  )
  
  // 表ID映射：binlog中的数字表ID -> TableId(database, table)
  private val tableIdMap = scala.collection.concurrent.TrieMap[Long, TableId]()
  
  // 连接状态
  @volatile private var isConnected = false
  
  // 配置客户端
  configureClient()
  
  private def configureClient(): Unit = {
    // 设置服务器 ID（随机生成避免冲突）
    client.setServerId((Math.random() * Int.MaxValue).toLong)
    
    // 启用心跳检测
    client.setKeepAlive(true)
    client.setKeepAliveInterval(60000) // 60 秒
    
    // 设置连接超时
    client.setConnectTimeout(30000) // 30 秒
  }
  
  override def start(startPosition: BinlogPosition): Source[RawBinlogEvent, NotUsed] = {
    logger.info(s"Starting binlog reader from position: ${startPosition.asString}")
    
    // 设置起始位置
    setStartPosition(startPosition)
    
    Source.queue[RawBinlogEvent](bufferSize, OverflowStrategy.backpressure)
      .mapMaterializedValue { queue =>
        // 注册事件监听器
        client.registerEventListener((event: Event) => {
          Try {
            processEvent(event)
          } match {
            case Success(Some(rawEvent)) =>
              queue.offer(rawEvent).onComplete {
                case Success(_) => // 成功入队
                case Failure(ex) =>
                  logger.error(s"Failed to offer event to queue: ${ex.getMessage}", ex)
              }
            case Success(None) =>
            // 事件被过滤，不处理
            case Failure(ex) =>
              logger.error(s"Failed to process binlog event: ${ex.getMessage}", ex)
          }
        })
        
        // 注册生命周期监听器
        client.registerLifecycleListener(new BinaryLogClient.LifecycleListener {
          override def onConnect(client: BinaryLogClient): Unit = {
            isConnected = true
            logger.info("Binlog client connected")
          }
          
          override def onCommunicationFailure(client: BinaryLogClient, ex: Exception): Unit = {
            logger.error(s"Binlog communication failure: ${ex.getMessage}", ex)
          }
          
          override def onEventDeserializationFailure(client: BinaryLogClient, ex: Exception): Unit = {
            logger.error(s"Event deserialization failure: ${ex.getMessage}", ex)
          }
          
          override def onDisconnect(client: BinaryLogClient): Unit = {
            isConnected = false
            logger.warn("Binlog client disconnected")
          }
        })
        
        // 异步连接
        Future {
          try {
            client.connect()
          } catch {
            case ex: Exception =>
              logger.error(s"Failed to connect to binlog: ${ex.getMessage}", ex)
              queue.fail(ex)
          }
        }
        
        NotUsed
      }
  }
  
  private def setStartPosition(position: BinlogPosition): Unit = {
    position match {
      case GTIDPosition(gtidSet) =>
        client.setGtidSet(gtidSet)
        logger.info(s"Set GTID position: $gtidSet")
        
      case FilePosition(filename, pos) =>
        if (filename.nonEmpty && pos > 0) {
          client.setBinlogFilename(filename)
          client.setBinlogPosition(pos)
          logger.info(s"Set file position: $filename:$pos")
        }
    }
    
    currentPosition.set(position)
  }
  
  private def processEvent(event: Event): Option[RawBinlogEvent] = {
    val header = event.getHeader[EventHeader]
    val eventType = header.getEventType
    
    // 更新当前位置
    updatePosition(event)
    
    eventType match {
      case EventType.WRITE_ROWS | EventType.EXT_WRITE_ROWS =>
        processWriteRowsEvent(event)
        
      case EventType.UPDATE_ROWS | EventType.EXT_UPDATE_ROWS =>
        processUpdateRowsEvent(event)
        
      case EventType.DELETE_ROWS | EventType.EXT_DELETE_ROWS =>
        processDeleteRowsEvent(event)
        
      case EventType.TABLE_MAP =>
        processTableMapEvent(event)
        None
        
      case EventType.QUERY =>
        processQueryEvent(event)
        
      case EventType.ROTATE =>
        processRotateEvent(event)
        None
        
      case EventType.FORMAT_DESCRIPTION =>
        logger.debug("Received FORMAT_DESCRIPTION event")
        None
        
      case EventType.XID =>
        logger.debug("Received XID event (transaction commit)")
        None
        
      case _ =>
        // 忽略其他事件类型
        None
    }
  }
  
  private def processWriteRowsEvent(event: Event): Option[RawBinlogEvent] = {
    val data = event.getData[WriteRowsEventData]
    val tableId = extractTableId(data.getTableId)
    
    val tableInfo = tableId.map(t => s"${t.database}.${t.table}").getOrElse(s"tableId=${data.getTableId}")
    logger.info(s"Received INSERT event for $tableInfo, rows: ${data.getRows.size()}")
    
    Some(RawBinlogEvent(
      position = currentPosition.get(),
      timestamp = Instant.ofEpochMilli(event.getHeader[EventHeaderV4].getTimestamp),
      eventType = WriteRowsEvent,
      tableId = tableId,
      rawData = data
    ))
  }
  
  private def processUpdateRowsEvent(event: Event): Option[RawBinlogEvent] = {
    val data = event.getData[UpdateRowsEventData]
    val tableId = extractTableId(data.getTableId)
    
    val tableInfo = tableId.map(t => s"${t.database}.${t.table}").getOrElse(s"tableId=${data.getTableId}")
    logger.info(s"Received UPDATE event for $tableInfo, rows: ${data.getRows.size()}")
    
    Some(RawBinlogEvent(
      position = currentPosition.get(),
      timestamp = Instant.ofEpochMilli(event.getHeader[EventHeaderV4].getTimestamp),
      eventType = UpdateRowsEvent,
      tableId = tableId,
      rawData = data
    ))
  }
  
  private def processDeleteRowsEvent(event: Event): Option[RawBinlogEvent] = {
    val data = event.getData[DeleteRowsEventData]
    val tableId = extractTableId(data.getTableId)
    
    val tableInfo = tableId.map(t => s"${t.database}.${t.table}").getOrElse(s"tableId=${data.getTableId}")
    logger.info(s"Received DELETE event for $tableInfo, rows: ${data.getRows.size()}")
    
    Some(RawBinlogEvent(
      position = currentPosition.get(),
      timestamp = Instant.ofEpochMilli(event.getHeader[EventHeaderV4].getTimestamp),
      eventType = DeleteRowsEvent,
      tableId = tableId,
      rawData = data
    ))
  }
  
  private def processQueryEvent(event: Event): Option[RawBinlogEvent] = {
    val data = event.getData[QueryEventData]
    val sql = data.getSql
    
    // 只处理 DDL 事件
    if (isDDL(sql)) {
      logger.warn(s"Detected DDL event: $sql")
      Some(RawBinlogEvent(
        position = currentPosition.get(),
        timestamp = Instant.ofEpochMilli(event.getHeader[EventHeaderV4].getTimestamp),
        eventType = QueryEvent,
        tableId = None,
        rawData = ByteString(sql.getBytes("UTF-8"))
      ))
    } else {
      None
    }
  }
  
  private def processRotateEvent(event: Event): Unit = {
    val data = event.getData[RotateEventData]
    val newFilename = data.getBinlogFilename
    val newPosition = data.getBinlogPosition
    
    currentPosition.set(FilePosition(newFilename, newPosition))
    logger.info(s"Binlog rotated to: $newFilename:$newPosition")
  }
  
  private def processTableMapEvent(event: Event): Unit = {
    val data = event.getData[TableMapEventData]
    val tableId = data.getTableId
    val database = data.getDatabase
    val table = data.getTable
    
    val tid = TableId(database, table)
    tableIdMap.put(tableId, tid)
    
    logger.debug(s"Table mapping: $tableId -> ${database}.${table}")
  }
  
  private def updatePosition(event: Event): Unit = {
    currentPosition.get() match {
      case FilePosition(filename, _) =>
        // 从事件头获取下一个位置
        // EventHeaderV4.getNextPosition() 返回当前事件结束后的位置
        val header = event.getHeader[EventHeaderV4]
        val nextPosition = header.getNextPosition
        
        if (nextPosition > 0) {
          val newPosition = FilePosition(filename, nextPosition)
          currentPosition.set(newPosition)
          logger.debug(s"Updated position to: ${newPosition.asString}")
        }
        
      case _: GTIDPosition =>
        // GTID 模式下位置由 MySQL 自动管理
        // 通过 client.getGtidSet() 获取当前 GTID
        val gtidSet = client.getGtidSet
        if (gtidSet != null && gtidSet.nonEmpty) {
          val newPosition = GTIDPosition(gtidSet)
          currentPosition.set(newPosition)
          logger.debug(s"Updated GTID position to: $gtidSet")
        }
    }
  }
  
  private def extractTableId(tableId: Long): Option[TableId] = {
    tableIdMap.get(tableId) match {
      case Some(tid) => Some(tid)
      case None =>
        logger.warn(s"Table ID $tableId not found in mapping, TABLE_MAP event may be missing")
        None
    }
  }
  
  private def isDDL(sql: String): Boolean = {
    val upperSql = sql.trim.toUpperCase
    upperSql.startsWith("CREATE") ||
    upperSql.startsWith("ALTER") ||
    upperSql.startsWith("DROP") ||
    upperSql.startsWith("TRUNCATE") ||
    upperSql.startsWith("RENAME")
  }
  
  override def getCurrentPosition(): BinlogPosition = {
    currentPosition.get()
  }
  
  override def stop(): Unit = {
    logger.info("Stopping binlog reader")
    try {
      if (isConnected) {
        client.disconnect()
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Error stopping binlog reader: ${ex.getMessage}", ex)
    }
  }
}

object MySQLBinlogReader {
  /**
   * 创建 Binlog Reader 实例
   */
  def apply(config: DatabaseConfig, bufferSize: Int = 1000)
           (implicit mat: Materializer, ec: ExecutionContext): MySQLBinlogReader = {
    new MySQLBinlogReader(config, bufferSize)
  }
}
