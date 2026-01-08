package cn.xuyinyin.cdc.normalizer

import cn.xuyinyin.cdc.model.ChangeEvent
import cn.xuyinyin.cdc.reader.RawBinlogEvent

/**
 * 事件标准化器接口
 * 将原始 binlog 事件转换为标准化的 ChangeEvent
 */
trait EventNormalizer {
  /**
   * 标准化 binlog 事件
   * 
   * @param rawEvent 原始 binlog 事件
   * @return 标准化的变更事件，如果事件不需要处理则返回 None
   */
  def normalize(rawEvent: RawBinlogEvent): Option[ChangeEvent]
}
