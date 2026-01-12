package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.connector.DataWriter
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/**
 * 快照工作器池
 * 管理一组快照工作器，负责分配和执行快照任务
 */
object SnapshotWorkerPool extends LazyLogging {
  
  def apply(
    poolSize: Int,
    catalogService: CatalogService,
    sink: DataWriter
  ): Behavior[SnapshotWorkerMessage] = {
    Behaviors.setup { context =>
      logger.info(s"Creating snapshot worker pool with $poolSize workers")
      
      // 创建工作器池
      val workers = (0 until poolSize).map { i =>
        context.spawn(
          SnapshotWorker.idle(catalogService, sink),
          s"snapshot-worker-$i"
        )
      }
      
      routing(workers.toVector, 0)
    }
  }
  
  /**
   * 路由消息到工作器（轮询方式）
   */
  private def routing(
    workers: Vector[ActorRef[SnapshotWorkerMessage]],
    nextWorkerIndex: Int
  ): Behavior[SnapshotWorkerMessage] = {
    Behaviors.receiveMessage { message =>
      // 将消息转发给下一个工作器
      val worker = workers(nextWorkerIndex)
      worker ! message
      
      // 更新索引（轮询）
      val newIndex = (nextWorkerIndex + 1) % workers.size
      routing(workers, newIndex)
    }
  }
}
