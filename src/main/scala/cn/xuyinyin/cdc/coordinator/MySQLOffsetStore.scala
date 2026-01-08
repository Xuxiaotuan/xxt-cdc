package cn.xuyinyin.cdc.coordinator

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.model.BinlogPosition
import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}

/**
 * 基于 MySQL 的偏移量存储实现
 * 使用独立的表存储偏移量信息
 * 
 * @param config 数据库配置
 * @param tableName 存储表名
 */
class MySQLOffsetStore(
  config: DatabaseConfig,
  tableName: String = "cdc_offsets"
)(implicit ec: ExecutionContext) extends OffsetStore with LazyLogging {

  // JDBC URL
  private val jdbcUrl = s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  
  // 初始化表结构
  initializeTable()
  
  private def initializeTable(): Unit = {
    Try {
      Using.resource(getConnection()) { conn =>
        val createTableSql =
          s"""
             |CREATE TABLE IF NOT EXISTS $tableName (
             |  id INT PRIMARY KEY AUTO_INCREMENT,
             |  position_type VARCHAR(20) NOT NULL,
             |  position_value TEXT NOT NULL,
             |  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
             |  INDEX idx_updated_at (updated_at)
             |)
             |""".stripMargin
        
        Using.resource(conn.createStatement()) { stmt =>
          stmt.execute(createTableSql)
        }
        
        logger.info(s"Initialized offset table: $tableName")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to initialize offset table: ${ex.getMessage}", ex)
    }
  }
  
  private def getConnection(): Connection = {
    DriverManager.getConnection(jdbcUrl, config.username, config.password)
  }
  
  override def save(position: BinlogPosition): Future[Unit] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        // 使用 REPLACE INTO 实现幂等性
        val sql =
          s"""
             |REPLACE INTO $tableName (id, position_type, position_value)
             |VALUES (1, ?, ?)
             |""".stripMargin
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          val (posType, posValue) = position match {
            case pos: cn.xuyinyin.cdc.model.GTIDPosition =>
              ("GTID", pos.gtidSet)
            case pos: cn.xuyinyin.cdc.model.FilePosition =>
              ("FILE", s"${pos.filename}:${pos.position}")
          }
          
          stmt.setString(1, posType)
          stmt.setString(2, posValue)
          stmt.executeUpdate()
        }
        
        logger.debug(s"Saved offset to MySQL: ${position.asString}")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to save offset to MySQL: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  override def load(): Future[Option[BinlogPosition]] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val sql =
          s"""
             |SELECT position_type, position_value
             |FROM $tableName
             |WHERE id = 1
             |ORDER BY updated_at DESC
             |LIMIT 1
             |""".stripMargin
        
        Using.resource(conn.createStatement()) { stmt =>
          Using.resource(stmt.executeQuery(sql)) { rs =>
            if (rs.next()) {
              val posType = rs.getString("position_type")
              val posValue = rs.getString("position_value")
              
              posType match {
                case "GTID" =>
                  Some(cn.xuyinyin.cdc.model.GTIDPosition(posValue))
                case "FILE" =>
                  BinlogPosition.parse(posValue)
                case _ =>
                  logger.warn(s"Unknown position type: $posType")
                  None
              }
            } else {
              None
            }
          }
        }
      }
    } match {
      case Success(position) =>
        position.foreach(pos => logger.info(s"Loaded offset from MySQL: ${pos.asString}"))
        position
      case Failure(ex) =>
        logger.error(s"Failed to load offset from MySQL: ${ex.getMessage}", ex)
        None
    }
  }
  
  override def delete(): Future[Unit] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val sql = s"DELETE FROM $tableName WHERE id = 1"
        
        Using.resource(conn.createStatement()) { stmt =>
          stmt.executeUpdate(sql)
        }
        
        logger.info("Deleted offset from MySQL")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to delete offset from MySQL: ${ex.getMessage}", ex)
        throw ex
    }
  }
}

object MySQLOffsetStore {
  /**
   * 创建 MySQL Offset Store 实例
   */
  def apply(config: DatabaseConfig, tableName: String = "cdc_offsets")
           (implicit ec: ExecutionContext): MySQLOffsetStore = {
    new MySQLOffsetStore(config, tableName)
  }
}
