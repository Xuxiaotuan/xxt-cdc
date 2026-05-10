package cn.xuyinyin.cdc.normalizer

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.model._
import cn.xuyinyin.cdc.reader.{DeleteRowsEvent, RawBinlogEvent, UpdateRowsEvent, WriteRowsEvent}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * Debezium Event Normalizer — 将 Debezium JSON 事件转换为内部 ChangeEvent 模型
 *
 * 与旧版 MySQLEventNormalizer 的关键区别：
 * - 接收 Debezium JSON 字符串（非 Shyiko 对象）
 * - 处理所有行（非仅第一行）
 * - 使用 Jackson 解析 JSON 结构，非 asInstanceOf 强转
 *
 * @param catalogService Catalog 服务，用于获取表元数据
 * @param sourceDatabase 源数据库名称
 */
class DebeziumEventNormalizer(
  catalogService: CatalogService,
  sourceDatabase: String
)(implicit ec: ExecutionContext) extends EventNormalizer with LazyLogging {

  private val objectMapper = new ObjectMapper()
  private val schemaCache = scala.collection.concurrent.TrieMap[TableId, TableSchema]()

  override def normalize(rawEvent: RawBinlogEvent): Option[ChangeEvent] = {
    rawEvent.tableId match {
      case Some(tableId) if tableId.database != sourceDatabase =>
        logger.debug(s"Skipping event from non-source database: ${tableId.database}.${tableId.table}")
        return None
      case None =>
        logger.debug("Skipping event without table ID")
        return None
      case _ =>
    }

    val jsonStr = rawEvent.rawData match {
      case s: String => s
      case other =>
        logger.error(s"Expected JSON String, got ${other.getClass.getName}")
        return None
    }

    Try(objectMapper.readTree(jsonStr)) match {
      case Success(root) => normalizeFromJson(root, rawEvent)
      case Failure(ex) =>
        logger.error(s"Failed to parse Debezium JSON: ${ex.getMessage}", ex)
        None
    }
  }

  private def normalizeFromJson(root: JsonNode, rawEvent: RawBinlogEvent): Option[ChangeEvent] = {
    val op = Option(root.get("op")).map(_.asText()).getOrElse("")
    val tableId = rawEvent.tableId.get

    op match {
      case "c" | "r" => normalizeRowChange(tableId, root, Insert, rawEvent)
      case "u"       => normalizeRowChange(tableId, root, Update, rawEvent)
      case "d"       => normalizeRowChange(tableId, root, Delete, rawEvent)
      case _ =>
        logger.debug(s"Skipping non-data operation: $op")
        None
    }
  }

  private def normalizeRowChange(
    tableId: TableId,
    root: JsonNode,
    operation: Operation,
    rawEvent: RawBinlogEvent
  ): Option[ChangeEvent] = {
    val beforeNode = Option(root.get("before")).filterNot(_.isNull)
    val afterNode  = Option(root.get("after")).filterNot(_.isNull)
    val tsMs       = Option(root.get("ts_ms")).map(_.asLong())

    val timestamp = tsMs.map(Instant.ofEpochMilli).getOrElse(rawEvent.timestamp)
    val position  = extractPosition(root, rawEvent.position)

    // 只处理 after 有数据的情况（INSERT/UPDATE 的 after、DELETE 的 before）
    // Debezium 的 DELETE 事件 only has "before" (null "after")
    val dataNode = operation match {
      case Delete => beforeNode
      case _      => afterNode
    }

    dataNode match {
      case Some(data) =>
        val rowData = nodeToMap(data)
        val primaryKey = extractPrimaryKeyFromNode(data, tableId)
        val beforeMap = beforeNode.map(nodeToMap)
        val afterMap  = afterNode.map(nodeToMap)

        Some(ChangeEvent(
          recordId = rawEvent.recordId,
          tableId = tableId,
          operation = operation,
          primaryKey = primaryKey,
          before = beforeMap,
          after = afterMap,
          timestamp = timestamp,
          position = position
        ))

      case None =>
        logger.warn(s"No data for ${operation} on ${tableId.database}.${tableId.table}")
        None
    }
  }

  private def nodeToMap(node: JsonNode): Map[String, Any] = {
    if (node == null || !node.isObject) return Map.empty

    node.fields().asScala.map { entry =>
      val key = entry.getKey
      val value = entry.getValue
      val converted = value match {
        case n if n.isInt     => n.asInt()
        case n if n.isLong    => n.asLong()
        case n if n.isDouble  => n.asDouble()
        case n if n.isBoolean => n.asBoolean()
        case n if n.isTextual => n.asText()
        case n if n.isNull    => null
        case other            => other.asText() // fallback
      }
      key -> converted
    }.toMap
  }

  private def extractPrimaryKeyFromNode(dataNode: JsonNode, tableId: TableId): Map[String, Any] = {
    val schema = schemaCache.get(tableId)
    val pkColumns = schema.map(_.primaryKeys).getOrElse(Seq.empty)

    if (pkColumns.isEmpty) {
      // 无法获取 schema 时，用数据中所有字段作为主键
      logger.warn(s"No primary key schema for ${tableId.database}.${tableId.table}, using all fields")
      nodeToMap(dataNode)
    } else {
      pkColumns.flatMap { col =>
        Option(dataNode.get(col)).map { valueNode =>
          col -> (valueNode match {
            case n if n.isInt     => n.asInt()
            case n if n.isLong    => n.asLong()
            case n if n.isTextual => n.asText()
            case other            => other.asText()
          })
        }
      }.toMap
    }
  }

  private def extractPosition(root: JsonNode, fallback: BinlogPosition): BinlogPosition = {
    val source = root.get("source")
    if (source == null) return fallback

    val gtid = Option(source.get("gtid")).map(_.asText()).filterNot(_.isEmpty)
    gtid match {
      case Some(g) => GTIDPosition(g)
      case None =>
        val file = Option(source.get("file")).map(_.asText()).getOrElse("")
        val pos  = Option(source.get("pos")).map(_.asLong()).getOrElse(0L)
        FilePosition(file, pos)
    }
  }
}

object DebeziumEventNormalizer {
  def apply(catalogService: CatalogService, sourceDatabase: String)
           (implicit ec: ExecutionContext): DebeziumEventNormalizer = {
    new DebeziumEventNormalizer(catalogService, sourceDatabase)
  }
}
