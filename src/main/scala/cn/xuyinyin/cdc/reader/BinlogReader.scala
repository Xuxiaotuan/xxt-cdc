package cn.xuyinyin.cdc.reader

import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.time.Instant

/**
 * Binlog Reader 接口
 * 单线程读取 binlog，保证事件全序
 */
trait BinlogReader {
  /**
   * 从指定位置开始读取 binlog 事件流
   * 
   * @param startPosition 起始位置
   * @return binlog 事件流
   */
  def start(startPosition: BinlogPosition): Source[RawBinlogEvent, NotUsed]
  
  /**
   * 获取当前读取位置
   * 
   * @return 当前 binlog 位置
   */
  def getCurrentPosition(): BinlogPosition
  
  /**
   * 停止读取
   */
  def stop(): Unit
}

/**
 * 原始 Binlog 事件
 * 
 * @param position binlog 位置
 * @param timestamp 事件时间戳
 * @param eventType 事件类型
 * @param tableId 表标识（如果是表相关事件）
 * @param rawData 原始事件数据对象
 */
case class RawBinlogEvent(
  position: BinlogPosition,
  timestamp: Instant,
  eventType: BinlogEventType,
  tableId: Option[TableId],
  rawData: Any
)

/**
 * Binlog 事件类型
 */
sealed trait BinlogEventType
case object WriteRowsEvent extends BinlogEventType
case object UpdateRowsEvent extends BinlogEventType
case object DeleteRowsEvent extends BinlogEventType
case object QueryEvent extends BinlogEventType
case object RotateEvent extends BinlogEventType
case object FormatDescriptionEvent extends BinlogEventType
case object XidEvent extends BinlogEventType
case object TableMapEvent extends BinlogEventType
