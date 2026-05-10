package cn.xuyinyin.cdc.reader

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.logging.CDCLogging
import cn.xuyinyin.cdc.model.{BinlogPosition, FilePosition, GTIDPosition, TableId}
import io.debezium.engine.{ChangeEvent, DebeziumEngine}
import io.debezium.engine.DebeziumEngine.{ChangeConsumer, RecordCommitter}
import io.debezium.engine.format.Json
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{Materializer, OverflowStrategy}

import java.util.Properties
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * 基于 Debezium Embedded Engine 的 Binlog Reader（v2）
 *
 * 相比 v1 的关键改进：
 * - 使用 FileOffsetBackingStore 替代 MemoryOffsetBackingStore（重启不丢 offset）
 * - 使用 ChangeConsumer + RecordCommitter 替代 Consumer（Sink 成功后才 ack）
 * - Schema history 按任务隔离存储
 * - 通过 AckRegistry 实现 Pekko 异步流与 Debezium 同步 RecordCommitter 的桥接
 *
 * @param config   源数据库配置
 * @param taskName 任务名称，用于隔离 offset/schema-history 文件
 * @param bufferSize Pekko Source.queue 缓冲区大小
 */
class DebeziumBinlogReader(
  config: DatabaseConfig,
  taskName: String,
  bufferSize: Int = 1000
)(implicit mat: Materializer, ec: ExecutionContext) extends BinlogReader with CDCLogging {

  private val currentPosition = new AtomicReference[BinlogPosition](
    FilePosition("", 0L)
  )

  @volatile private var engine: Option[DebeziumEngine[ChangeEvent[String, String]]] = None

  /** 批次计数器，用于生成唯一 recordId */
  private val batchCounter = new java.util.concurrent.atomic.AtomicLong(0)

  /**
   * Ack 注册表 — 桥接 Pekko 异步流和 Debezium 同步 RecordCommitter
   * 以 recordId 为 key，避免同 position 多条记录被一次性 ack
   */
  private val ackRegistry = new AckRegistry()

  override def start(startPosition: BinlogPosition): Source[RawBinlogEvent, NotUsed] = {
    logger.info(s"[${taskName}] Starting Debezium binlog reader from: ${startPosition.asString}")

    Source.queue[RawBinlogEvent](bufferSize, OverflowStrategy.backpressure)
      .mapMaterializedValue { queue =>

        val debeziumConfig = buildDebeziumConfig(startPosition)

        val debeziumEngine = DebeziumEngine.create(classOf[Json])
          .using(debeziumConfig)
          .notifying(new ChangeConsumer[ChangeEvent[String, String]] {
            override def handleBatch(
              records: java.util.List[ChangeEvent[String, String]],
              committer: RecordCommitter[ChangeEvent[String, String]]
            ): Unit = {
              val batchSize = records.size()
              val batchId = s"${taskName}-b${batchCounter.incrementAndGet()}"
              val pending = new AtomicInteger(batchSize)

              records.asScala.zipWithIndex.foreach { case (record, idx) =>
                val recordId = s"$batchId-r$idx"
                try {
                  convertDebeziumRecord(record, recordId).foreach { rawEvent =>
                    // 注册 ack 回调：以 recordId 为 key，精确对应单条 Debezium record
                    ackRegistry.register(recordId) { () =>
                      committer.markProcessed(record)
                      if (pending.decrementAndGet() == 0) {
                        committer.markBatchFinished()
                        logger.debug(s"[${taskName}] Batch $batchId of $batchSize records acked")
                      }
                    }

                    queue.offer(rawEvent).foreach { result =>
                      if (!result.isEnqueued) {
                        logger.warn(s"[${taskName}] Failed to enqueue event $recordId")
                      }
                    }
                  }
                } catch {
                  case ex: Exception =>
                    logger.error(s"[${taskName}] Failed to process Debezium record $recordId: ${ex.getMessage}", ex)
                    throw ex
                }
              }
            }
          })
          .using(new DebeziumEngine.CompletionCallback {
            override def handle(success: Boolean, message: String, error: Throwable): Unit = {
              if (success) {
                logger.info(s"[${taskName}] Debezium engine completed: $message")
              } else {
                logger.error(s"[${taskName}] Debezium engine failed: $message", error)
                queue.fail(error)
              }
            }
          })
          .build()

        engine = Some(debeziumEngine)

        Future {
          try {
            logger.info(s"[${taskName}] Starting Debezium engine...")
            debeziumEngine.run()
          } catch {
            case ex: Exception =>
              logger.error(s"[${taskName}] Debezium engine error: ${ex.getMessage}", ex)
              queue.fail(ex)
          }
        }

        NotUsed
      }
  }

  /**
   * 当 Pipeline 安全提交 record 后调用，触发 Debezium RecordCommitter.markProcessed
   * @param recordId 唯一记录标识（非 position，避免同 position 多条记录被误 ack）
   */
  def ack(recordId: String): Unit = {
    ackRegistry.ack(recordId)
  }

  private def buildDebeziumConfig(startPosition: BinlogPosition): Properties = {
    val props = new Properties()

    props.setProperty("name", s"mysql-cdc-${taskName}")
    props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector")

    props.setProperty("database.hostname", config.host)
    props.setProperty("database.port", config.port.toString)
    props.setProperty("database.user", config.username)
    props.setProperty("database.password", config.password)
    props.setProperty("database.server.id", generateServerId().toString)
    props.setProperty("topic.prefix", s"mysql-cdc-${taskName}")

    props.setProperty("database.include.list", config.database)

    if (config.debeziumConfig.tableIncludeList.nonEmpty) {
      props.setProperty("table.include.list", config.debeziumConfig.tableIncludeList)
      logger.info(s"[${taskName}] Table filter: ${config.debeziumConfig.tableIncludeList}")
    } else {
      logger.info(s"[${taskName}] Monitoring all tables in: ${config.database}")
    }

    // ====== 生死线修复：使用 FileOffsetBackingStore 替代 MemoryOffset ======
    props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore")
    props.setProperty("offset.storage.file.filename", s"data/offsets/${taskName}.dat")
    props.setProperty("offset.flush.interval.ms", "10000")

    // ====== 生死线修复：Schema history 按任务隔离，不用 /tmp ======
    props.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory")
    props.setProperty("schema.history.internal.file.filename", s"data/schema-history/${taskName}.dat")

    // Snapshot: 默认 never，由上层 CDCEngine 控制 snapshot/catchup 阶段
    props.setProperty("snapshot.mode", config.debeziumConfig.snapshotMode)

    startPosition match {
      case GTIDPosition(gtidSet) =>
        props.setProperty("database.history.skip.unparseable.ddl", "true")
        props.setProperty("gtid.source.includes", gtidSet)
        logger.info(s"[${taskName}] GTID mode: $gtidSet")
      case FilePosition(filename, pos) if filename.nonEmpty && pos > 0 =>
        logger.info(s"[${taskName}] File position from offset store: $filename:$pos")
      case _ =>
        logger.info(s"[${taskName}] Starting from current binlog position")
    }

    props.setProperty("errors.max.retries", config.debeziumConfig.errorsMaxRetries.toString)
    props.setProperty("errors.retry.delay.initial.ms", "1000")
    props.setProperty("errors.retry.delay.max.ms", "30000")

    props.setProperty("connect.timeout.ms", "30000")
    props.setProperty("connect.keep.alive", "true")
    props.setProperty("connect.keep.alive.interval.ms", "60000")

    props.setProperty("provide.transaction.metadata", "false")
    props.setProperty("include.schema.changes", "false")

    props.setProperty("max.batch.size", config.debeziumConfig.maxBatchSize.toString)
    props.setProperty("max.queue.size", config.debeziumConfig.maxQueueSize.toString)
    props.setProperty("poll.interval.ms", config.debeziumConfig.pollIntervalMs.toString)

    props.setProperty("time.precision.mode", "connect")
    props.setProperty("signal.enabled.channels", "source")

    logger.info(s"[${taskName}] Debezium configured: snapshot.mode=${props.getProperty("snapshot.mode")}, " +
      s"offset.storage=${props.getProperty("offset.storage")}, " +
      s"batch=${props.getProperty("max.batch.size")}")

    props
  }

  private def convertDebeziumRecord(record: ChangeEvent[String, String], recordId: String): Option[RawBinlogEvent] = {
    val value = record.value()
    if (value == null || value.isEmpty) {
      logger.debug("Received null or empty value from Debezium")
      return None
    }

    Try {
      val operation = extractOperation(value)
      if (operation.isEmpty) {
        logger.warn(s"Skipping record without operation type")
        return None
      }

      val tableInfo = extractTableInfo(value)
      if (tableInfo.isEmpty) {
        logger.warn(s"Skipping record without table information")
        return None
      }

      val (database, table) = tableInfo.get
      val tableId = TableId(database, table)
      val position = extractPositionFromValue(value)
      currentPosition.set(position)
      val timestamp = extractTimestamp(value).getOrElse(java.time.Instant.now())

      Some(RawBinlogEvent(
        recordId = recordId,
        position = position,
        timestamp = timestamp,
        eventType = mapOperationType(operation.get),
        tableId = Some(tableId),
        rawData = value
      ))
    }.toOption.flatten
  }

  private def extractOperation(json: String): Option[String] = {
    val opPattern = """"op"\s*:\s*"([cudr])"""".r
    opPattern.findFirstMatchIn(json).map(_.group(1))
  }

  private def extractTableInfo(json: String): Option[(String, String)] = {
    val dbPattern = """"source"\s*:\s*\{[^}]*"db"\s*:\s*"([^"]+)"""".r
    val tablePattern = """"source"\s*:\s*\{[^}]*"table"\s*:\s*"([^"]+)"""".r

    for {
      db <- dbPattern.findFirstMatchIn(json).map(_.group(1))
      table <- tablePattern.findFirstMatchIn(json).map(_.group(1))
    } yield (db, table)
  }

  private def extractPositionFromValue(json: String): BinlogPosition = {
    val filePattern = """"file"\s*:\s*"([^"]+)"""".r
    val posPattern = """"pos"\s*:\s*(\d+)""".r
    val gtidPattern = """"gtid"\s*:\s*"([^"]+)"""".r

    gtidPattern.findFirstMatchIn(json).map(_.group(1)) match {
      case Some(gtid) if gtid.nonEmpty => GTIDPosition(gtid)
      case _ =>
        val file = filePattern.findFirstMatchIn(json).map(_.group(1)).getOrElse("")
        val pos = posPattern.findFirstMatchIn(json).map(_.group(1).toLong).getOrElse(0L)
        FilePosition(file, pos)
    }
  }

  private def extractTimestamp(json: String): Option[java.time.Instant] = {
    val tsPattern = """"ts_ms"\s*:\s*(\d+)""".r
    tsPattern.findFirstMatchIn(json).map(_.group(1).toLong)
      .map(java.time.Instant.ofEpochMilli)
  }

  private def mapOperationType(op: String): BinlogEventType = op match {
    case "c" => WriteRowsEvent
    case "u" => UpdateRowsEvent
    case "d" => DeleteRowsEvent
    case "r" => WriteRowsEvent
    case _   => WriteRowsEvent
  }

  private def generateServerId(): Long = {
    new scala.util.Random().nextInt(60000) + 5000L
  }

  override def getCurrentPosition(): BinlogPosition = currentPosition.get()

  override def stop(): Unit = {
    logger.info(s"[${taskName}] Stopping Debezium binlog reader")
    engine.foreach { e =>
      try e.close()
      catch { case ex: Exception =>
        logger.error(s"[${taskName}] Error stopping Debezium: ${ex.getMessage}", ex)
      }
    }
  }
}

object DebeziumBinlogReader {
  def apply(
    config: DatabaseConfig,
    taskName: String,
    bufferSize: Int = 1000
  )(implicit mat: Materializer, ec: ExecutionContext): DebeziumBinlogReader = {
    new DebeziumBinlogReader(config, taskName, bufferSize)
  }
}

/**
 * Ack 注册表 — 桥接异步 Pekko 流和同步 Debezium RecordCommitter
 *
 * 以 recordId 为 key，避免同 binlog position 下多条记录被一次性 markProcessed。
 * Pipeline 在安全提交后调用 ack(recordId)，触发对应 Debezium record 的 markProcessed。
 */
class AckRegistry {
  private val callbacks = new ConcurrentHashMap[String, java.util.List[() => Unit]]()

  def register(recordId: String)(callback: () => Unit): Unit = {
    callbacks.computeIfAbsent(recordId, _ => new java.util.concurrent.CopyOnWriteArrayList[() => Unit]())
    callbacks.get(recordId).add(callback)
  }

  def ack(recordId: String): Unit = {
    Option(callbacks.remove(recordId)).foreach { list =>
      list.asScala.foreach { cb =>
        try cb()
        catch { case ex: Exception => /* ack 失败不阻塞 */ }
      }
    }
  }
}
