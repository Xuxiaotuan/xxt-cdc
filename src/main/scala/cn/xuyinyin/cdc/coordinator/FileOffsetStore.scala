package cn.xuyinyin.cdc.coordinator

import cn.xuyinyin.cdc.model.BinlogPosition
import com.typesafe.scalalogging.LazyLogging

import java.io.{File, PrintWriter}
import java.nio.file.{Files, StandardCopyOption}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success, Try, Using}

/**
 * 基于文件的偏移量存储实现
 * 使用原子性写入确保数据一致性
 * 
 * @param filePath 存储文件路径
 */
class FileOffsetStore(filePath: String)(implicit ec: ExecutionContext) 
  extends OffsetStore with LazyLogging {

  private val offsetFile = new File(filePath)
  private val tempFile = new File(filePath + ".tmp")
  
  // 确保目录存在
  ensureDirectoryExists()
  
  private def ensureDirectoryExists(): Unit = {
    val parentDir = offsetFile.getParentFile
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs()
      logger.info(s"Created offset directory: ${parentDir.getAbsolutePath}")
    }
  }
  
  override def save(position: BinlogPosition): Future[Unit] = Future {
    Try {
      // 先写入临时文件
      Using(new PrintWriter(tempFile)) { writer =>
        writer.println(position.asString)
      }.get
      
      // 原子性地移动到目标文件
      Files.move(
        tempFile.toPath,
        offsetFile.toPath,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
      
      logger.debug(s"Saved offset to file: ${position.asString}")
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to save offset to file: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  override def load(): Future[Option[BinlogPosition]] = {
    if (!offsetFile.exists()) {
      logger.info("Offset file does not exist")
      return Future.successful(None)
    }
    
    Future {
      Try {
        Using(Source.fromFile(offsetFile)) { source =>
          val lines = source.getLines().toList
          if (lines.nonEmpty) {
            BinlogPosition.parse(lines.head.trim)
          } else {
            None
          }
        }.get
      } match {
        case Success(position) =>
          position.foreach(pos => logger.info(s"Loaded offset from file: ${pos.asString}"))
          position
        case Failure(ex) =>
          logger.error(s"Failed to load offset from file: ${ex.getMessage}", ex)
          None
      }
    }
  }
  
  override def delete(): Future[Unit] = Future {
    Try {
      if (offsetFile.exists()) {
        offsetFile.delete()
        logger.info("Deleted offset file")
      }
      if (tempFile.exists()) {
        tempFile.delete()
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to delete offset file: ${ex.getMessage}", ex)
        throw ex
    }
  }
}

object FileOffsetStore {
  /**
   * 创建 File Offset Store 实例
   */
  def apply(filePath: String)(implicit ec: ExecutionContext): FileOffsetStore = {
    new FileOffsetStore(filePath)
  }
}
