package cn.xuyinyin.cdc.pipeline

import cn.xuyinyin.cdc.config.CDCConfig
import cn.xuyinyin.cdc.coordinator.OffsetCoordinator
import cn.xuyinyin.cdc.model.{BinlogPosition, ChangeEvent}
import cn.xuyinyin.cdc.normalizer.EventNormalizer
import cn.xuyinyin.cdc.reader.{BinlogReader, RawBinlogEvent}
import cn.xuyinyin.cdc.router.EventRouter
import cn.xuyinyin.cdc.worker.ApplyWorker
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, Partition, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ActorAttributes, ClosedShape, Materializer, Supervision}
import org.apache.pekko.Done

import scala.concurrent.{ExecutionContext, Future}

/**
 * CDC Stream Pipeline
 * 构建端到端的数据处理管道：
 * Binlog Reader → Event Normalizer → Router → Apply Workers → Offset Coordinator
 * 
 * @param config CDC 配置
 * @param binlogReader Binlog 读取器
 * @param eventNormalizer 事件标准化器
 * @param eventRouter 事件路由器
 * @param applyWorkers Apply Worker 列表（每个分区一个）
 * @param offsetCoordinator 偏移量协调器
 * @param metrics CDC 指标收集器
 */
class CDCStreamPipeline(
  config: CDCConfig,
  binlogReader: BinlogReader,
  eventNormalizer: EventNormalizer,
  eventRouter: EventRouter,
  applyWorkers: Seq[ApplyWorker],
  offsetCoordinator: OffsetCoordinator,
  metrics: Option[cn.xuyinyin.cdc.metrics.CDCMetrics] = None
)(implicit mat: Materializer, ec: ExecutionContext) extends LazyLogging {

  private val partitionCount = config.parallelism.partitionCount
  private val batchSize = config.parallelism.batchSize
  private val flushInterval = config.parallelism.flushInterval
  
  /**
   * 构建并运行 CDC 流处理管道
   * 
   * @param startPosition 起始 binlog 位置
   * @return 管道完成的 Future
   */
  def run(startPosition: BinlogPosition): Future[Done] = {
    logger.info(s"Starting CDC stream pipeline from position: ${startPosition.asString}")
    
    val graph = createPipelineGraph(startPosition)
    
    graph.run()
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
              // 标记为 RECEIVED
              offsetCoordinator.markReceived(rawEvent.position)
              
              // 标准化事件
              val events = eventNormalizer.normalize(rawEvent).toList
              
              // 记录 ingest 指标
              if (events.nonEmpty) {
                metrics.foreach(_.recordIngest(rawEvent.position, rawEvent.timestamp))
              }
              
              events
            }
            .withAttributes(ActorAttributes.supervisionStrategy(decider))
        )
        
        // 3. Router/Partitioner
        val partitioner = builder.add(
          Partition[ChangeEvent](partitionCount, event => eventRouter.route(event))
        )
        
        // 4. Apply Workers (每个分区一个)
        val mergeApplied = builder.add(Merge[Done](partitionCount))
        
        for (partition <- 0 until partitionCount) {
          val applyFlow = Flow[ChangeEvent]
            .groupedWithin(batchSize, flushInterval)
            .mapAsync(1) { events =>
              applyWorkers(partition).apply(events).map { result =>
                if (result.failedEvents.nonEmpty) {
                  logger.warn(s"Partition $partition: ${result.failedEvents.size} events failed")
                }
                Done
              }
            }
            .withAttributes(ActorAttributes.supervisionStrategy(decider))
          
          val applyFlowShape = builder.add(applyFlow)
          partitioner.out(partition) ~> applyFlowShape ~> mergeApplied
        }
        
        // 5. Offset Committer
        val offsetCommitter = builder.add(
          Flow[Done]
            .conflate((_, _) => Done) // 合并多个 Done 信号
            .throttle(1, config.offset.commitInterval) // 限制提交频率
            .mapAsync(1) { _ =>
              commitOffset()
            }
            .withAttributes(ActorAttributes.supervisionStrategy(decider))
        )
        
        // 连接所有组件
        binlogSource ~> normalizerFlow ~> partitioner
        mergeApplied ~> offsetCommitter ~> sinkShape
        
        ClosedShape
    })
  }
  
  private def commitOffset(): Future[Done] = {
    offsetCoordinator.getCommittablePosition() match {
      case Some(position) =>
        offsetCoordinator.commit(position).map { _ =>
          logger.info(s"Committed offset: ${position.asString}")
          Done
        }.recover { case ex =>
          logger.error(s"Failed to commit offset: ${ex.getMessage}", ex)
          Done
        }
      case None =>
        logger.debug("No committable position available")
        Future.successful(Done)
    }
  }
  
  /**
   * 监督策略：决定如何处理流中的异常
   */
  private val decider: Supervision.Decider = {
    case ex: Exception =>
      logger.error(s"Stream error: ${ex.getMessage}", ex)
      // 继续处理，不中断流
      Supervision.Resume
    case ex: Throwable =>
      logger.error(s"Fatal stream error: ${ex.getMessage}", ex)
      // 致命错误，停止流
      Supervision.Stop
  }
  
  /**
   * 停止管道
   */
  def stop(): Unit = {
    logger.info("Stopping CDC stream pipeline")
    binlogReader.stop()
  }
}

object CDCStreamPipeline {
  /**
   * 创建 CDC Stream Pipeline 实例
   */
  def apply(
    config: CDCConfig,
    binlogReader: BinlogReader,
    eventNormalizer: EventNormalizer,
    eventRouter: EventRouter,
    applyWorkers: Seq[ApplyWorker],
    offsetCoordinator: OffsetCoordinator,
    metrics: Option[cn.xuyinyin.cdc.metrics.CDCMetrics] = None
  )(implicit mat: Materializer, ec: ExecutionContext): CDCStreamPipeline = {
    new CDCStreamPipeline(
      config,
      binlogReader,
      eventNormalizer,
      eventRouter,
      applyWorkers,
      offsetCoordinator,
      metrics
    )
  }
}
