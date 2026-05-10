package cn.xuyinyin.cdc.pipeline

import cn.xuyinyin.cdc.config.CDCConfig
import cn.xuyinyin.cdc.coordinator.OffsetCoordinator
import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent}
import cn.xuyinyin.cdc.normalizer.EventNormalizer
import cn.xuyinyin.cdc.reader.{BinlogReader, DebeziumBinlogReader, RawBinlogEvent}
import cn.xuyinyin.cdc.router.EventRouter
import cn.xuyinyin.cdc.worker.ApplyWorker
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, Partition, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ActorAttributes, ClosedShape, Materializer, Supervision}
import org.apache.pekko.Done

import scala.concurrent.{ExecutionContext, Future}

/**
 * CDC Stream Pipeline（v3）
 *
 * v2 → v3 关键改进：
 * - recordId 贯穿全链路（AckRegistry 用 recordId，非 position）
 * - offset 提交使用 continuous checkpoint（getCommittablePosition + commit）
 * - 同 partition 内的事件由 ApplyWorker 负责顺序（见 DefaultApplyWorker 改动）
 *
 * @param config            CDC 配置
 * @param binlogReader      Binlog 读取器
 * @param eventNormalizer   事件标准化器（DebeziumEventNormalizer）
 * @param eventRouter       事件路由器
 * @param applyWorkers      Apply Worker 列表
 * @param offsetCoordinator 偏移量协调器
 */
class CDCStreamPipeline(
  config: CDCConfig,
  binlogReader: BinlogReader,
  eventNormalizer: EventNormalizer,
  eventRouter: EventRouter,
  applyWorkers: Seq[ApplyWorker],
  offsetCoordinator: OffsetCoordinator
)(implicit mat: Materializer, ec: ExecutionContext) extends LazyLogging {

  private val partitionCount = config.parallelism.partitionCount
  private val batchSize      = config.parallelism.batchSize
  private val flushInterval  = config.parallelism.flushInterval

  /** 待 ack 的 batch 队列—详见 [[AckQueue]]。 */
  private val ackQueue = new AckQueue()

  def run(startPosition: BinlogPosition): Future[Done] = {
    logger.info(s"Starting CDC stream pipeline from position: ${startPosition.asString}")
    createPipelineGraph(startPosition).run()
  }

  private def createPipelineGraph(startPosition: BinlogPosition): RunnableGraph[Future[Done]] = {
    RunnableGraph.fromGraph(GraphDSL.createGraph(Sink.ignore) { implicit builder =>
      sinkShape =>
        import GraphDSL.Implicits._

        // 1. Binlog Reader Source
        val binlogSource = builder.add(
          binlogReader.start(startPosition)
            .withAttributes(ActorAttributes.supervisionStrategy(decider))
        )

        // 2. Event Normalizer Flow
        val normalizerFlow = builder.add(
          Flow[RawBinlogEvent]
            .mapConcat { rawEvent =>
              offsetCoordinator.markReceived(rawEvent.position)
              eventNormalizer.normalize(rawEvent).toList
            }
            .withAttributes(ActorAttributes.supervisionStrategy(decider))
        )

        // 3. Router/Partitioner
        val partitioner = builder.add(
          Partition[ChangeEvent](partitionCount, event => eventRouter.route(event))
        )

        // 4. Apply Workers — 失败即停，成功返回 (position, recordIds)
        val mergeApplied = builder.add(Merge[Either[Throwable, AppliedBatch]](partitionCount))

        for (partition <- 0 until partitionCount) {
          val applyFlow = Flow[ChangeEvent]
            .groupedWithin(batchSize, flushInterval)
            .mapAsync(1) { events =>
              applyWorkers(partition).apply(events).flatMap { result =>
                if (result.failedEvents.nonEmpty) {
                  val errors = result.failedEvents.map { case (ev, ex) =>
                    s"${ev.tableId.database}.${ev.tableId.table}(${ev.operation}): ${ex.getMessage}"
                  }.mkString("; ")
                  val msg = s"Partition $partition: ${result.failedEvents.size} events failed: $errors"
                  logger.error(msg)
                  Future.failed(new RuntimeException(msg))
                } else {
                  val lastPos = result.lastAppliedPosition
                  val recordIds = events.map(_.recordId).toVector
                  offsetCoordinator.markApplied(partition, lastPos)
                  // 入队等待 commit checkpoint 推进后批量 ack（按 recordId 精确 ack）
                  ackQueue.offer(lastPos, recordIds)
                  logger.debug(s"Partition $partition: ${result.successCount} events applied, pos=$lastPos, rids=${recordIds.take(3)}...")
                  Future.successful(Right(AppliedBatch(lastPos, recordIds)))
                }
              }
            }
            .withAttributes(ActorAttributes.supervisionStrategy(decider))

          val applyFlowShape = builder.add(applyFlow)
          partitioner.out(partition) ~> applyFlowShape ~> mergeApplied
        }

        // 5. Offset Committer — 使用 continuous checkpoint
        //   - 用 groupedWithin 节流（不用 conflate，避免丢 recordIds）
        //   - 每个时间窗内累积所有 batch；窗口结束后做一次 commit 检查
        //   - 即便没有新 batch（empty group 已被 filter 过滤），也可触发 ack drain
        val offsetCommitter = builder.add(
          Flow[Either[Throwable, AppliedBatch]]
            .collect { case Right(batch) => batch }
            .groupedWithin(1024, config.offset.commitInterval)
            .filter(_.nonEmpty)
            .mapAsync(1) { _ =>
              offsetCoordinator.getCommittablePosition() match {
                case Some(checkpoint) =>
                  val recordIdsToAck = ackQueue.drainUpTo(checkpoint)
                  commitAndAck(checkpoint, recordIdsToAck)
                case None =>
                  Future.successful(Done)
              }
            }
            .withAttributes(ActorAttributes.supervisionStrategy(decider))
        )

        binlogSource ~> normalizerFlow ~> partitioner
        mergeApplied ~> offsetCommitter ~> sinkShape

        ClosedShape
    })
  }

  private def commitAndAck(position: BinlogPosition, recordIdsToAck: Vector[String]): Future[Done] = {
    offsetCoordinator.commit(position).map { _ =>
      logger.info(s"Committed checkpoint: ${position.asString}, ack ${recordIdsToAck.size} records")

      binlogReader match {
        case dbr: DebeziumBinlogReader =>
          // 真闭环：commit checkpoint 成功后，按 recordId 精确触发
          // Debezium RecordCommitter.markProcessed（通过 AckRegistry 桥接）
          recordIdsToAck.foreach(dbr.ack)
        case _ =>
      }
      Done
    }.recover { case ex =>
      logger.error(s"Failed to commit checkpoint ${position.asString}: ${ex.getMessage}", ex)
      throw ex
    }
  }

  private val decider: Supervision.Decider = {
    case ex: Exception =>
      logger.error(s"CDC stream fatal error, stopping pipeline: ${ex.getMessage}", ex)
      Supervision.Stop
    case ex: Throwable =>
      logger.error(s"CDC stream fatal throwable, stopping pipeline: ${ex.getMessage}", ex)
      Supervision.Stop
  }

  def stop(): Unit = {
    logger.info("Stopping CDC stream pipeline")
    binlogReader.stop()
  }
}

object CDCStreamPipeline {
  def apply(
    config: CDCConfig,
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    eventRouter: EventRouter,
    applyWorkers: Seq[ApplyWorker],
    offsetCoordinator: OffsetCoordinator
  )(implicit mat: Materializer, ec: ExecutionContext): CDCStreamPipeline = {
    new CDCStreamPipeline(config, binlogReader, eventNormalizer, eventRouter, applyWorkers, offsetCoordinator)
  }
}

/** 记录一个成功应用的批次 */
private[pipeline] case class AppliedBatch(
  lastPosition: BinlogPosition,
  recordIds: Vector[String]
)
