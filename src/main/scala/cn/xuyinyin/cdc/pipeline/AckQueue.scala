package cn.xuyinyin.cdc.pipeline

import cn.xuyinyin.cdc.model.{BinlogPosition, FilePosition, GTIDPosition}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec

/**
 * 待 ack 的 batch 队列（FIFO）。
 *
 * 每个 batch 记录 `(lastPosition, recordIds)`：
 *   - 写入：`offer` — apply 成功后由 mapAsync 入队
 *   - 读取：`drainUpTo(checkpoint)` — commit checkpoint 推进时
 *           取出所有 `pos <= checkpoint` 的条目
 *
 * 不能用 `conflate` 节流 — conflate 会丢中间 batch 的 recordIds，
 * 导致对应 Debezium record 的 markProcessed 永不调用 → AckRegistry
 * 内存泄漏 + 重启回退到最老 offset。
 *
 * 线程安全：底层 `ConcurrentLinkedQueue` 提供无锁单生产-单消费的弱一致语义；
 * Pipeline 中只有 mapAsync(1) 一个写入点 + offsetCommitter 一个读取点，
 * 满足该约束。
 */
class AckQueue {

  private val queue = new ConcurrentLinkedQueue[(BinlogPosition, Vector[String])]()

  /** 入队一个 batch（apply 成功后调用）。 */
  def offer(position: BinlogPosition, recordIds: Vector[String]): Unit = {
    queue.offer((position, recordIds))
  }

  /**
   * 取出所有 `pos <= checkpoint` 的 recordIds（保留入队顺序）。
   *
   * 必须在 commit 之前调用以快照可 ack 集合，避免 commit 期间新 batch
   * 错误地被纳入本次 ack 范围。
   *
   * 注意：仅 head 在范围内才出队。如果 head > checkpoint，立即停止
   *       —— 因为后续 batch 必然 >= head（FIFO 入队），都不在范围内。
   *       这要求**单生产者按 position 单调非降序入队**，Pipeline
   *       的 mapAsync(1) 串行 apply 满足该约束。
   */
  def drainUpTo(checkpoint: BinlogPosition): Vector[String] = {
    val builder = Vector.newBuilder[String]
    drainLoop(checkpoint, builder)
    builder.result()
  }

  @tailrec
  private def drainLoop(checkpoint: BinlogPosition, builder: scala.collection.mutable.Builder[String, Vector[String]]): Unit = {
    val head = queue.peek()
    if (head == null || AckQueue.comparePositions(head._1, checkpoint) > 0) {
      ()
    } else {
      Option(queue.poll()).foreach { case (_, rids) => builder ++= rids }
      drainLoop(checkpoint, builder)
    }
  }

  /** 当前队列长度（仅监控/测试用，弱一致）。 */
  def pendingCount(): Int = queue.size()

  /** 当前队列是否为空（弱一致）。 */
  def isEmpty: Boolean = queue.isEmpty
}

object AckQueue {

  /**
   * Position 比较 — 与 DefaultOffsetCoordinator 内部一致。
   *
   * TODO: 抽到 BinlogPosition 伴生对象的 Ordering，避免重复实现。
   */
  private[pipeline] def comparePositions(a: BinlogPosition, b: BinlogPosition): Int = {
    (a, b) match {
      case (FilePosition(f1, p1), FilePosition(f2, p2)) =>
        val fc = f1.compareTo(f2)
        if (fc != 0) fc else java.lang.Long.compare(p1, p2)
      case (GTIDPosition(g1), GTIDPosition(g2)) => g1.compareTo(g2)
      case _                                    => 0
    }
  }
}
