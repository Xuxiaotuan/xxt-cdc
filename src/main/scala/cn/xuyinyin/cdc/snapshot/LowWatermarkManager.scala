package cn.xuyinyin.cdc.snapshot

import cn.xuyinyin.cdc.model.{BinlogPosition, TableId}
import cn.xuyinyin.cdc.reader.BinlogReader
import com.typesafe.scalalogging.LazyLogging

import java.sql.{DriverManager, ResultSet}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

/**
 * Low Watermark 记录
 * 记录快照开始时的 binlog 位置，用于后续的 catchup 处理
 */
case class LowWatermark(
  tableId: TableId,
  position: BinlogPosition,
  snapshotId: String,
  createdAt: Instant = Instant.now(),
  status: WatermarkStatus = WatermarkStatus.Active
) {
  def isActive: Boolean = status == WatermarkStatus.Active
  def isCompleted: Boolean = status == WatermarkStatus.Completed
  def isExpired: Boolean = status == WatermarkStatus.Expired
}

/**
 * Watermark 状态
 */
sealed trait WatermarkStatus
object WatermarkStatus {
  case object Active extends WatermarkStatus      // 活跃状态，快照正在进行
  case object Completed extends WatermarkStatus  // 已完成，快照和catchup都完成
  case object Expired extends WatermarkStatus    // 已过期，需要重新快照
}

/**
 * Low Watermark 管理器
 * 负责管理快照过程中的 binlog 位置记录
 */
class LowWatermarkManager(
  binlogReader: BinlogReader,
  jdbcUrl: String,
  username: String,
  password: String
)(implicit ec: ExecutionContext) extends LazyLogging {
  
  // 初始化存储表
  initializeStorage()
  
  /**
   * 创建 Low Watermark
   * 在快照开始前记录当前的 binlog 位置
   */
  def createLowWatermark(
    tableId: TableId,
    snapshotId: String
  ): Future[LowWatermark] = {
    Future {
      logger.info(s"Creating low watermark for table $tableId, snapshot $snapshotId")
      
      // 获取当前 binlog 位置
      val currentPosition = binlogReader.getCurrentPosition()
      logger.debug(s"Current binlog position: $currentPosition")
      
      val watermark = LowWatermark(
        tableId = tableId,
        position = currentPosition,
        snapshotId = snapshotId
      )
      
      // 持久化到数据库
      saveLowWatermark(watermark)
      
      logger.info(s"Low watermark created for table $tableId at position $currentPosition")
      watermark
    }
  }
  
  /**
   * 获取表的 Low Watermark
   */
  def getLowWatermark(tableId: TableId): Future[Option[LowWatermark]] = {
    Future {
      loadLowWatermark(tableId)
    }
  }
  
  /**
   * 获取快照的 Low Watermark
   */
  def getLowWatermarkBySnapshot(snapshotId: String): Future[Option[LowWatermark]] = {
    Future {
      loadLowWatermarkBySnapshot(snapshotId)
    }
  }
  
  /**
   * 列出所有活跃的 Low Watermarks
   */
  def listActiveLowWatermarks(): Future[Seq[LowWatermark]] = {
    Future {
      loadActiveLowWatermarks()
    }
  }
  
  /**
   * 更新 Watermark 状态
   */
  def updateWatermarkStatus(
    tableId: TableId,
    snapshotId: String,
    status: WatermarkStatus
  ): Future[Unit] = {
    Future {
      logger.info(s"Updating watermark status for table $tableId, snapshot $snapshotId to $status")
      updateWatermarkStatusInDb(tableId, snapshotId, status)
    }
  }
  
  /**
   * 完成 Watermark
   * 标记快照和 catchup 都已完成
   */
  def completeLowWatermark(
    tableId: TableId,
    snapshotId: String
  ): Future[Unit] = {
    updateWatermarkStatus(tableId, snapshotId, WatermarkStatus.Completed)
  }
  
  /**
   * 过期 Watermark
   * 标记 watermark 已过期，需要重新快照
   */
  def expireLowWatermark(
    tableId: TableId,
    snapshotId: String
  ): Future[Unit] = {
    updateWatermarkStatus(tableId, snapshotId, WatermarkStatus.Expired)
  }
  
  /**
   * 清理过期的 Watermarks
   */
  def cleanupExpiredWatermarks(olderThanHours: Int = 24): Future[Int] = {
    Future {
      logger.info(s"Cleaning up watermarks older than $olderThanHours hours")
      
      val cutoffTime = Instant.now().minusSeconds(olderThanHours * 3600)
      val deletedCount = deleteExpiredWatermarks(cutoffTime)
      
      logger.info(s"Cleaned up $deletedCount expired watermarks")
      deletedCount
    }
  }
  
  /**
   * 获取 Watermark 统计信息
   */
  def getWatermarkStatistics(): Future[WatermarkStatistics] = {
    Future {
      calculateWatermarkStatistics()
    }
  }
  
  /**
   * 验证 Watermark 一致性
   * 检查是否有孤立的 watermark 记录
   */
  def validateWatermarkConsistency(): Future[Seq[String]] = {
    Future {
      val issues = scala.collection.mutable.ListBuffer[String]()
      
      // 检查是否有过期的活跃 watermarks
      val activeWatermarksOlderThan24Hours = loadActiveLowWatermarks()
        .filter(_.createdAt.isBefore(Instant.now().minusSeconds(24 * 3600)))
      
      if (activeWatermarksOlderThan24Hours.nonEmpty) {
        issues += s"Found ${activeWatermarksOlderThan24Hours.size} active watermarks older than 24 hours"
      }
      
      // 检查是否有重复的 watermarks
      val allWatermarks = loadAllLowWatermarks()
      val duplicates = allWatermarks.groupBy(w => (w.tableId, w.snapshotId))
        .filter(_._2.size > 1)
      
      if (duplicates.nonEmpty) {
        issues += s"Found ${duplicates.size} duplicate watermark entries"
      }
      
      issues.toSeq
    }
  }
  
  // === 私有方法：数据库操作 ===
  
  private def initializeStorage(): Unit = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val createTableSql = """
        CREATE TABLE IF NOT EXISTS cdc_low_watermarks (
          table_database VARCHAR(255) NOT NULL,
          table_name VARCHAR(255) NOT NULL,
          snapshot_id VARCHAR(255) NOT NULL,
          position_type VARCHAR(50) NOT NULL,
          position_value TEXT NOT NULL,
          status VARCHAR(50) NOT NULL DEFAULT 'Active',
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
          PRIMARY KEY (table_database, table_name, snapshot_id),
          INDEX idx_status (status),
          INDEX idx_created_at (created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
      """
      
      Using(conn.prepareStatement(createTableSql)) { stmt =>
        stmt.executeUpdate()
      }
      
      logger.info("Low watermark storage table initialized")
    }.recover { error =>
      logger.error("Failed to initialize low watermark storage", error)
      throw error
    }.get
  }
  
  private def saveLowWatermark(watermark: LowWatermark): Unit = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val insertSql = """
        INSERT INTO cdc_low_watermarks 
        (table_database, table_name, snapshot_id, position_type, position_value, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
        position_type = VALUES(position_type),
        position_value = VALUES(position_value),
        status = VALUES(status),
        updated_at = CURRENT_TIMESTAMP
      """
      
      Using(conn.prepareStatement(insertSql)) { stmt =>
        stmt.setString(1, watermark.tableId.database)
        stmt.setString(2, watermark.tableId.table)
        stmt.setString(3, watermark.snapshotId)
        stmt.setString(4, watermark.position.getClass.getSimpleName)
        stmt.setString(5, serializePosition(watermark.position))
        stmt.setString(6, watermark.status.toString)
        stmt.setTimestamp(7, java.sql.Timestamp.from(watermark.createdAt))
        
        stmt.executeUpdate()
      }
    }.get
  }
  
  private def loadLowWatermark(tableId: TableId): Option[LowWatermark] = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val selectSql = """
        SELECT snapshot_id, position_type, position_value, status, created_at
        FROM cdc_low_watermarks
        WHERE table_database = ? AND table_name = ? AND status = 'Active'
        ORDER BY created_at DESC
        LIMIT 1
      """
      
      Using(conn.prepareStatement(selectSql)) { stmt =>
        stmt.setString(1, tableId.database)
        stmt.setString(2, tableId.table)
        
        Using(stmt.executeQuery()) { rs =>
          if (rs.next()) {
            Some(parseWatermarkFromResultSet(rs, tableId))
          } else {
            None
          }
        }.get
      }.get
    }.get
  }
  
  private def loadLowWatermarkBySnapshot(snapshotId: String): Option[LowWatermark] = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val selectSql = """
        SELECT table_database, table_name, position_type, position_value, status, created_at
        FROM cdc_low_watermarks
        WHERE snapshot_id = ?
      """
      
      Using(conn.prepareStatement(selectSql)) { stmt =>
        stmt.setString(1, snapshotId)
        
        Using(stmt.executeQuery()) { rs =>
          if (rs.next()) {
            val tableId = TableId(rs.getString("table_database"), rs.getString("table_name"))
            Some(parseWatermarkFromResultSet(rs, tableId, Some(snapshotId)))
          } else {
            None
          }
        }.get
      }.get
    }.get
  }
  
  private def loadActiveLowWatermarks(): Seq[LowWatermark] = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val selectSql = """
        SELECT table_database, table_name, snapshot_id, position_type, position_value, status, created_at
        FROM cdc_low_watermarks
        WHERE status = 'Active'
        ORDER BY created_at DESC
      """
      
      Using(conn.prepareStatement(selectSql)) { stmt =>
        Using(stmt.executeQuery()) { rs =>
          val watermarks = scala.collection.mutable.ListBuffer[LowWatermark]()
          
          while (rs.next()) {
            val tableId = TableId(rs.getString("table_database"), rs.getString("table_name"))
            val snapshotId = rs.getString("snapshot_id")
            watermarks += parseWatermarkFromResultSet(rs, tableId, Some(snapshotId))
          }
          
          watermarks.toSeq
        }.get
      }.get
    }.get
  }
  
  private def loadAllLowWatermarks(): Seq[LowWatermark] = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val selectSql = """
        SELECT table_database, table_name, snapshot_id, position_type, position_value, status, created_at
        FROM cdc_low_watermarks
        ORDER BY created_at DESC
      """
      
      Using(conn.prepareStatement(selectSql)) { stmt =>
        Using(stmt.executeQuery()) { rs =>
          val watermarks = scala.collection.mutable.ListBuffer[LowWatermark]()
          
          while (rs.next()) {
            val tableId = TableId(rs.getString("table_database"), rs.getString("table_name"))
            val snapshotId = rs.getString("snapshot_id")
            watermarks += parseWatermarkFromResultSet(rs, tableId, Some(snapshotId))
          }
          
          watermarks.toSeq
        }.get
      }.get
    }.get
  }
  
  private def updateWatermarkStatusInDb(
    tableId: TableId,
    snapshotId: String,
    status: WatermarkStatus
  ): Unit = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val updateSql = """
        UPDATE cdc_low_watermarks
        SET status = ?, updated_at = CURRENT_TIMESTAMP
        WHERE table_database = ? AND table_name = ? AND snapshot_id = ?
      """
      
      Using(conn.prepareStatement(updateSql)) { stmt =>
        stmt.setString(1, status.toString)
        stmt.setString(2, tableId.database)
        stmt.setString(3, tableId.table)
        stmt.setString(4, snapshotId)
        
        val updatedRows = stmt.executeUpdate()
        if (updatedRows == 0) {
          logger.warn(s"No watermark found to update for table $tableId, snapshot $snapshotId")
        }
      }
    }.get
  }
  
  private def deleteExpiredWatermarks(cutoffTime: Instant): Int = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val deleteSql = """
        DELETE FROM cdc_low_watermarks
        WHERE status IN ('Completed', 'Expired') AND created_at < ?
      """
      
      Using(conn.prepareStatement(deleteSql)) { stmt =>
        stmt.setTimestamp(1, java.sql.Timestamp.from(cutoffTime))
        stmt.executeUpdate()
      }.get
    }.get
  }
  
  private def calculateWatermarkStatistics(): WatermarkStatistics = {
    Using(DriverManager.getConnection(jdbcUrl, username, password)) { conn =>
      val statsSql = """
        SELECT 
          status,
          COUNT(*) as count,
          MIN(created_at) as oldest,
          MAX(created_at) as newest
        FROM cdc_low_watermarks
        GROUP BY status
      """
      
      Using(conn.prepareStatement(statsSql)) { stmt =>
        Using(stmt.executeQuery()) { rs =>
          var totalWatermarks = 0
          var activeWatermarks = 0
          var completedWatermarks = 0
          var expiredWatermarks = 0
          var oldestWatermark: Option[Instant] = None
          var newestWatermark: Option[Instant] = None
          
          while (rs.next()) {
            val status = rs.getString("status")
            val count = rs.getInt("count")
            val oldest = Option(rs.getTimestamp("oldest")).map(_.toInstant)
            val newest = Option(rs.getTimestamp("newest")).map(_.toInstant)
            
            totalWatermarks += count
            
            status match {
              case "Active" => activeWatermarks = count
              case "Completed" => completedWatermarks = count
              case "Expired" => expiredWatermarks = count
              case _ => // ignore unknown status
            }
            
            oldestWatermark = oldestWatermark.orElse(oldest).orElse(oldest.flatMap(o => 
              oldestWatermark.map(existing => if (o.isBefore(existing)) o else existing)
            ))
            
            newestWatermark = newestWatermark.orElse(newest).orElse(newest.flatMap(n => 
              newestWatermark.map(existing => if (n.isAfter(existing)) n else existing)
            ))
          }
          
          WatermarkStatistics(
            totalWatermarks = totalWatermarks,
            activeWatermarks = activeWatermarks,
            completedWatermarks = completedWatermarks,
            expiredWatermarks = expiredWatermarks,
            oldestWatermark = oldestWatermark,
            newestWatermark = newestWatermark
          )
        }.get
      }.get
    }.get
  }
  
  private def parseWatermarkFromResultSet(
    rs: ResultSet,
    tableId: TableId,
    snapshotId: Option[String] = None
  ): LowWatermark = {
    val actualSnapshotId = snapshotId.getOrElse(rs.getString("snapshot_id"))
    val positionType = rs.getString("position_type")
    val positionValue = rs.getString("position_value")
    val status = parseWatermarkStatus(rs.getString("status"))
    val createdAt = rs.getTimestamp("created_at").toInstant
    
    val position = deserializePosition(positionType, positionValue)
    
    LowWatermark(
      tableId = tableId,
      position = position,
      snapshotId = actualSnapshotId,
      createdAt = createdAt,
      status = status
    )
  }
  
  private def parseWatermarkStatus(status: String): WatermarkStatus = {
    status match {
      case "Active" => WatermarkStatus.Active
      case "Completed" => WatermarkStatus.Completed
      case "Expired" => WatermarkStatus.Expired
      case _ => WatermarkStatus.Active // 默认状态
    }
  }
  
  private def serializePosition(position: BinlogPosition): String = {
    // 简化的序列化，实际实现应该使用JSON或其他格式
    position.toString
  }
  
  private def deserializePosition(positionType: String, positionValue: String): BinlogPosition = {
    // 简化的反序列化，实际实现应该根据类型解析
    // 这里需要根据实际的 BinlogPosition 实现来调整
    cn.xuyinyin.cdc.model.FilePosition("", 0) // 占位符实现
  }
}

/**
 * Watermark 统计信息
 */
case class WatermarkStatistics(
  totalWatermarks: Int,
  activeWatermarks: Int,
  completedWatermarks: Int,
  expiredWatermarks: Int,
  oldestWatermark: Option[Instant],
  newestWatermark: Option[Instant]
) {
  def toMap: Map[String, Any] = Map(
    "totalWatermarks" -> totalWatermarks,
    "activeWatermarks" -> activeWatermarks,
    "completedWatermarks" -> completedWatermarks,
    "expiredWatermarks" -> expiredWatermarks,
    "oldestWatermark" -> oldestWatermark.map(_.toString).orNull,
    "newestWatermark" -> newestWatermark.map(_.toString).orNull
  )
}

object LowWatermarkManager {
  /**
   * 创建 Low Watermark 管理器
   */
  def apply(
    binlogReader: BinlogReader,
    jdbcUrl: String,
    username: String,
    password: String
  )(implicit ec: ExecutionContext): LowWatermarkManager = {
    new LowWatermarkManager(binlogReader, jdbcUrl, username, password)
  }
}