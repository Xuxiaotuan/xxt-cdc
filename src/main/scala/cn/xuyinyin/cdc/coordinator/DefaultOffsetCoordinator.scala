package cn.xuyinyin.cdc.coordinator

import cn.xuyinyin.cdc.model.{Applied, BinlogPosition, Committed, FilePosition, GTIDPosition, OffsetState, Received}
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/**
 * 默认的偏移量协调器实现
 * 管理偏移量的状态转换：RECEIVED → APPLIED → COMMITTED
 * 
 * @param partitionCount 分区数量
 * @param offsetStore 偏移量存储
 */
class DefaultOffsetCoordinator(
  partitionCount: Int,
  offsetStore: OffsetStore
)(implicit ec: ExecutionContext) extends OffsetCoordinator with LazyLogging {

  // 每个分区的偏移量状态
  // Key: partition -> position, Value: state
  private val partitionOffsets = new ConcurrentHashMap[Int, ConcurrentHashMap[String, OffsetState]]()
  
  // 已接收的偏移量（全局顺序）
  private val receivedPositions = new java.util.concurrent.ConcurrentLinkedQueue[BinlogPosition]()
  
  // 最后已提交的偏移量
  @volatile private var lastCommittedPosition: Option[BinlogPosition] = None
  
  // 初始化分区映射
  (0 until partitionCount).foreach { partition =>
    partitionOffsets.put(partition, new ConcurrentHashMap[String, OffsetState]())
  }
  
  // 从存储加载最后已提交的偏移量
  loadLastCommittedPosition()
  
  private def loadLastCommittedPosition(): Unit = {
    offsetStore.load().onComplete {
      case scala.util.Success(Some(position)) =>
        lastCommittedPosition = Some(position)
        logger.info(s"Loaded last committed position: ${position.asString}")
      case scala.util.Success(None) =>
        logger.info("No previous committed position found")
      case scala.util.Failure(ex) =>
        logger.error(s"Failed to load last committed position: ${ex.getMessage}", ex)
    }
  }
  
  override def markReceived(position: BinlogPosition): Unit = {
    receivedPositions.add(position)
    logger.debug(s"Marked position as RECEIVED: ${position.asString}")
  }
  
  override def markApplied(partition: Int, position: BinlogPosition): Unit = {
    require(partition >= 0 && partition < partitionCount, s"Invalid partition: $partition")
    
    val positionKey = position.asString
    val partitionMap = partitionOffsets.get(partition)
    
    // 验证状态转换
    val currentState = Option(partitionMap.get(positionKey)).getOrElse(Received)
    
    if (OffsetState.isValidTransition(currentState, Applied)) {
      partitionMap.put(positionKey, Applied)
      logger.debug(s"Marked position as APPLIED in partition $partition: $positionKey")
    } else {
      logger.warn(s"Invalid state transition from $currentState to APPLIED for position: $positionKey")
    }
  }
  
  override def getCommittablePosition(): Option[BinlogPosition] = {
    // 找到所有分区都已 APPLIED 的最大连续位置
    val positions = receivedPositions.asScala.toList
    
    if (positions.isEmpty) {
      return None
    }
    
    // 从前往后检查，找到第一个不是所有分区都 APPLIED 的位置
    var committablePosition: Option[BinlogPosition] = None
    
    for (position <- positions) {
      if (isAppliedInAllPartitions(position)) {
        committablePosition = Some(position)
      } else {
        // 找到第一个未完全应用的位置，停止
        return committablePosition
      }
    }
    
    committablePosition
  }
  
  private def isAppliedInAllPartitions(position: BinlogPosition): Boolean = {
    val positionKey = position.asString
    
    (0 until partitionCount).forall { partition =>
      val partitionMap = partitionOffsets.get(partition)
      val state = Option(partitionMap.get(positionKey))
      
      // 如果该分区没有这个位置的记录，说明该位置的事件没有路由到这个分区
      // 这种情况下认为该分区已"完成"
      state.isEmpty || state.contains(Applied) || state.contains(Committed)
    }
  }
  
  override def commit(position: BinlogPosition): Future[Unit] = {
    val positionKey = position.asString
    
    // 验证所有分区都已 APPLIED
    if (!isAppliedInAllPartitions(position)) {
      return Future.failed(new IllegalStateException(
        s"Cannot commit position $positionKey: not all partitions have applied it"
      ))
    }
    
    // 持久化到存储
    offsetStore.save(position).map { _ =>
      // 更新所有分区的状态为 COMMITTED
      (0 until partitionCount).foreach { partition =>
        val partitionMap = partitionOffsets.get(partition)
        val currentState = Option(partitionMap.get(positionKey))
        
        if (currentState.isDefined && OffsetState.isValidTransition(currentState.get, Committed)) {
          partitionMap.put(positionKey, Committed)
        }
      }
      
      // 更新最后已提交位置
      lastCommittedPosition = Some(position)
      
      // 清理已提交的位置
      cleanupCommittedPositions(position)
      
      logger.info(s"Committed position: $positionKey")
    }.recoverWith { case ex =>
      logger.error(s"Failed to commit position $positionKey: ${ex.getMessage}", ex)
      Future.failed(ex)
    }
  }
  
  private def cleanupCommittedPositions(committedPosition: BinlogPosition): Unit = {
    // 从 receivedPositions 中移除已提交的位置
    val iterator = receivedPositions.iterator()
    while (iterator.hasNext) {
      val position = iterator.next()
      if (comparePositions(position, committedPosition) <= 0) {
        iterator.remove()
        
        // 从分区映射中移除
        val positionKey = position.asString
        (0 until partitionCount).foreach { partition =>
          partitionOffsets.get(partition).remove(positionKey)
        }
      }
    }
  }
  
  private def comparePositions(pos1: BinlogPosition, pos2: BinlogPosition): Int = {
    (pos1, pos2) match {
      case (FilePosition(file1, offset1), FilePosition(file2, offset2)) =>
        if (file1 == file2) {
          offset1.compareTo(offset2)
        } else {
          // 简单的文件名比较（实际应该解析文件序号）
          file1.compareTo(file2)
        }
      case (GTIDPosition(gtid1), GTIDPosition(gtid2)) =>
        // GTID 比较较复杂，这里简化处理
        gtid1.compareTo(gtid2)
      case _ =>
        // 不同类型的位置无法比较
        0
    }
  }
  
  override def getLastCommittedPosition(): Option[BinlogPosition] = {
    lastCommittedPosition
  }
  
  /**
   * 获取当前状态统计信息（用于监控）
   */
  def getStatistics(): OffsetStatistics = {
    val receivedCount = receivedPositions.size()
    val appliedCount = (0 until partitionCount).map { partition =>
      partitionOffsets.get(partition).values().asScala.count(_ == Applied)
    }.sum
    val committedCount = (0 until partitionCount).map { partition =>
      partitionOffsets.get(partition).values().asScala.count(_ == Committed)
    }.sum
    
    OffsetStatistics(
      receivedCount = receivedCount,
      appliedCount = appliedCount,
      committedCount = committedCount,
      lastCommittedPosition = lastCommittedPosition
    )
  }
}

/**
 * 偏移量统计信息
 */
case class OffsetStatistics(
  receivedCount: Int,
  appliedCount: Int,
  committedCount: Int,
  lastCommittedPosition: Option[BinlogPosition]
)

object DefaultOffsetCoordinator {
  /**
   * 创建 Offset Coordinator 实例
   */
  def apply(partitionCount: Int, offsetStore: OffsetStore)
           (implicit ec: ExecutionContext): DefaultOffsetCoordinator = {
    new DefaultOffsetCoordinator(partitionCount, offsetStore)
  }
}
