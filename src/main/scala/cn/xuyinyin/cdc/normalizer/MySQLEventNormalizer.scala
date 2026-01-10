package cn.xuyinyin.cdc.normalizer

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.model._
import cn.xuyinyin.cdc.reader.{DeleteRowsEvent, RawBinlogEvent, UpdateRowsEvent, WriteRowsEvent}
import com.github.shyiko.mysql.binlog.event.{DeleteRowsEventData, UpdateRowsEventData, WriteRowsEventData}
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * MySQL Event Normalizer 实现
 * 将原始 binlog 事件转换为标准化的 ChangeEvent
 * 
 * @param catalogService Catalog 服务，用于获取表元数据
 * @param sourceDatabase 源数据库名称，用于过滤事件
 */
class MySQLEventNormalizer(
  catalogService: CatalogService,
  sourceDatabase: String
)(implicit ec: ExecutionContext) extends EventNormalizer with LazyLogging {

  // 缓存表结构信息
  private val schemaCache = scala.collection.concurrent.TrieMap[TableId, TableSchema]()
  
  override def normalize(rawEvent: RawBinlogEvent): Option[ChangeEvent] = {
    // 过滤：只处理源数据库的表
    rawEvent.tableId match {
      case Some(tableId) if tableId.database != sourceDatabase =>
        logger.debug(s"Skipping event from non-source database: ${tableId.database}.${tableId.table}")
        return None
      case None =>
        logger.debug("Skipping event without table ID")
        return None
      case _ => // 继续处理
    }
    
    // 记录事件详情
    val tableInfo = rawEvent.tableId.map(t => s"${t.database}.${t.table}").getOrElse("unknown")
    logger.info(s"Processing ${rawEvent.eventType} event for table: $tableInfo at position: ${rawEvent.position.asString}")
    
    rawEvent.eventType match {
      case WriteRowsEvent =>
        normalizeInsertEvent(rawEvent)
        
      case UpdateRowsEvent =>
        normalizeUpdateEvent(rawEvent)
        
      case DeleteRowsEvent =>
        normalizeDeleteEvent(rawEvent)
        
      case _ =>
        // 其他事件类型暂不处理
        None
    }
  }
  
  private def normalizeInsertEvent(rawEvent: RawBinlogEvent): Option[ChangeEvent] = {
    Try {
      val data = rawEvent.rawData.asInstanceOf[WriteRowsEventData]
      val tableId = rawEvent.tableId.getOrElse(return None)
      
      // 获取表结构
      val schema = getTableSchema(tableId)
      
      // 处理每一行插入
      val rows = data.getRows.asScala
      if (rows.isEmpty) return None
      
      // 暂时只处理第一行（实际应该为每行生成一个事件）
      val row = rows.head
      val rowData = convertRowToMap(row, schema)
      val primaryKey = extractPrimaryKey(rowData, schema)
      
      Some(ChangeEvent(
        tableId = tableId,
        operation = Insert,
        primaryKey = primaryKey,
        before = None,
        after = Some(rowData),
        timestamp = rawEvent.timestamp,
        position = rawEvent.position
      ))
    } match {
      case Success(event) => event
      case Failure(ex) =>
        logger.error(s"Failed to normalize INSERT event: ${ex.getMessage}", ex)
        None
    }
  }
  
  private def normalizeUpdateEvent(rawEvent: RawBinlogEvent): Option[ChangeEvent] = {
    Try {
      val data = rawEvent.rawData.asInstanceOf[UpdateRowsEventData]
      val tableId = rawEvent.tableId.getOrElse(return None)
      
      // 获取表结构
      val schema = getTableSchema(tableId)
      
      // 处理每一行更新
      val rows = data.getRows.asScala
      if (rows.isEmpty) return None
      
      // 暂时只处理第一行
      val row = rows.head
      val beforeData = convertRowToMap(row.getKey, schema)
      val afterData = convertRowToMap(row.getValue, schema)
      val primaryKey = extractPrimaryKey(afterData, schema)
      
      Some(ChangeEvent(
        tableId = tableId,
        operation = Update,
        primaryKey = primaryKey,
        before = Some(beforeData),
        after = Some(afterData),
        timestamp = rawEvent.timestamp,
        position = rawEvent.position
      ))
    } match {
      case Success(event) => event
      case Failure(ex) =>
        logger.error(s"Failed to normalize UPDATE event: ${ex.getMessage}", ex)
        None
    }
  }
  
  private def normalizeDeleteEvent(rawEvent: RawBinlogEvent): Option[ChangeEvent] = {
    Try {
      val data = rawEvent.rawData.asInstanceOf[DeleteRowsEventData]
      val tableId = rawEvent.tableId.getOrElse(return None)
      
      // 获取表结构
      val schema = getTableSchema(tableId)
      
      // 处理每一行删除
      val rows = data.getRows.asScala
      if (rows.isEmpty) return None
      
      // 暂时只处理第一行
      val row = rows.head
      val rowData = convertRowToMap(row, schema)
      val primaryKey = extractPrimaryKey(rowData, schema)
      
      Some(ChangeEvent(
        tableId = tableId,
        operation = Delete,
        primaryKey = primaryKey,
        before = Some(rowData),
        after = None,
        timestamp = rawEvent.timestamp,
        position = rawEvent.position
      ))
    } match {
      case Success(event) => event
      case Failure(ex) =>
        logger.error(s"Failed to normalize DELETE event: ${ex.getMessage}", ex)
        None
    }
  }
  
  private def getTableSchema(tableId: TableId): TableSchema = {
    schemaCache.getOrElseUpdate(tableId, {
      // 同步获取表结构（实际应该异步处理）
      import scala.concurrent.Await
        import scala.concurrent.duration._
      Await.result(catalogService.getTableSchema(tableId), 10.seconds)
    })
  }
  
  private def convertRowToMap(row: Array[java.io.Serializable], schema: TableSchema): Map[String, Any] = {
    schema.columns.zipWithIndex.map { case (column, index) =>
      val value = if (index < row.length) {
        convertValue(row(index), column.dataType)
      } else {
        null
      }
      column.name -> value
    }.toMap
  }
  
  private def convertValue(value: java.io.Serializable, dataType: MySQLDataType): Any = {
    if (value == null) return null
    
    dataType match {
      case TinyInt | SmallInt | MediumInt | Int =>
        value match {
          case n: Number => n.intValue()
          case _ => value
        }
        
      case BigInt =>
        value match {
          case n: Number => n.longValue()
          case _ => value
        }
        
      case Decimal(_, _) =>
        value match {
          case bd: java.math.BigDecimal => bd
          case n: Number => new java.math.BigDecimal(n.toString)
          case _ => value
        }
        
      case Float =>
        value match {
          case n: Number => n.floatValue()
          case _ => value
        }
        
      case Double =>
        value match {
          case n: Number => n.doubleValue()
          case _ => value
        }
        
      case VarChar(_) | Char(_) | Text | LongText =>
        value.toString
        
      case DateTime | Timestamp =>
        value match {
          case d: java.util.Date => Instant.ofEpochMilli(d.getTime)
          case ts: java.sql.Timestamp => ts.toInstant
          case _ => value
        }
        
      case Date =>
        value match {
          case d: java.sql.Date => d.toLocalDate
          case _ => value
        }
        
      case Time =>
        value match {
          case t: java.sql.Time => t.toLocalTime
          case _ => value
        }
        
      case Json =>
        value.toString
        
      case Blob =>
        value match {
          case bytes: Array[Byte] => bytes
          case _ => value
        }
    }
  }
  
  private def extractPrimaryKey(rowData: Map[String, Any], schema: TableSchema): Map[String, Any] = {
    schema.primaryKeys.flatMap { pkColumn =>
      rowData.get(pkColumn).map(pkColumn -> _)
    }.toMap
  }
}

object MySQLEventNormalizer {
  /**
   * 创建 Event Normalizer 实例
   */
  def apply(catalogService: CatalogService, sourceDatabase: String)
           (implicit ec: ExecutionContext): MySQLEventNormalizer = {
    new MySQLEventNormalizer(catalogService, sourceDatabase)
  }
}
