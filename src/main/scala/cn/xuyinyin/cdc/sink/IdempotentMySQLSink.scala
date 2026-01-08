package cn.xuyinyin.cdc.sink

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.model.TableId
import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager, PreparedStatement, SQLException}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}

/**
 * 幂等的 MySQL Sink 实现
 * 使用特殊的 SQL 语法确保操作的幂等性
 * 
 * @param config 目标数据库配置
 */
class IdempotentMySQLSink(config: DatabaseConfig)(implicit ec: ExecutionContext) 
  extends MySQLSink with LazyLogging {

  // JDBC URL
  private val jdbcUrl = s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
  
  // Statement 缓存
  private val statementCache = scala.collection.concurrent.TrieMap[String, String]()
  
  private def getConnection(): Connection = {
    DriverManager.getConnection(jdbcUrl, config.username, config.password)
  }
  
  override def executeInsert(table: TableId, data: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val sql = getOrCreateInsertStatement(table, data.keys.toSeq)
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          setParameters(stmt, data.values.toSeq ++ data.values.toSeq)
          stmt.executeUpdate()
        }
        
        logger.debug(s"Executed idempotent INSERT: $table")
      }
    } match {
      case Success(_) => ()
      case Failure(ex: SQLException) if isDuplicateKeyError(ex) =>
        // 重复键错误在幂等插入中是正常的
        logger.debug(s"Duplicate key on INSERT (expected): $table")
      case Failure(ex) =>
        logger.error(s"Failed to execute INSERT: $table", ex)
        throw ex
    }
  }
  
  private def getOrCreateInsertStatement(table: TableId, columns: Seq[String]): String = {
    val cacheKey = s"INSERT:${table}:${columns.mkString(",")}"
    
    statementCache.getOrElseUpdate(cacheKey, {
      val columnList = columns.mkString(", ")
      val placeholders = columns.map(_ => "?").mkString(", ")
      val updateClause = columns.map(col => s"$col = VALUES($col)").mkString(", ")
      
      s"""
         |INSERT INTO ${table.database}.${table.table} ($columnList)
         |VALUES ($placeholders)
         |ON DUPLICATE KEY UPDATE $updateClause
         |""".stripMargin
    })
  }
  
  override def executeUpdate(table: TableId, pk: Map[String, Any], data: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val sql = getOrCreateUpdateStatement(table, pk.keys.toSeq, data.keys.toSeq)
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          // 先设置 SET 子句的参数，再设置 WHERE 子句的参数
          setParameters(stmt, data.values.toSeq ++ pk.values.toSeq)
          val affectedRows = stmt.executeUpdate()
          
          if (affectedRows == 0) {
            logger.warn(s"UPDATE affected 0 rows (record may not exist): $table, pk: $pk")
          }
        }
        
        logger.debug(s"Executed idempotent UPDATE: $table")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to execute UPDATE: $table", ex)
        throw ex
    }
  }
  
  private def getOrCreateUpdateStatement(
    table: TableId,
    pkColumns: Seq[String],
    dataColumns: Seq[String]
  ): String = {
    val cacheKey = s"UPDATE:${table}:${pkColumns.mkString(",")}:${dataColumns.mkString(",")}"
    
    statementCache.getOrElseUpdate(cacheKey, {
      val setClause = dataColumns.map(col => s"$col = ?").mkString(", ")
      val whereClause = pkColumns.map(col => s"$col = ?").mkString(" AND ")
      
      s"""
         |UPDATE ${table.database}.${table.table}
         |SET $setClause
         |WHERE $whereClause
         |""".stripMargin
    })
  }
  
  override def executeDelete(table: TableId, pk: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val sql = getOrCreateDeleteStatement(table, pk.keys.toSeq)
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          setParameters(stmt, pk.values.toSeq)
          val affectedRows = stmt.executeUpdate()
          
          if (affectedRows == 0) {
            // 记录不存在是正常的（幂等性）
            logger.debug(s"DELETE affected 0 rows (record already deleted): $table, pk: $pk")
          }
        }
        
        logger.debug(s"Executed idempotent DELETE: $table")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to execute DELETE: $table", ex)
        throw ex
    }
  }
  
  private def getOrCreateDeleteStatement(table: TableId, pkColumns: Seq[String]): String = {
    val cacheKey = s"DELETE:${table}:${pkColumns.mkString(",")}"
    
    statementCache.getOrElseUpdate(cacheKey, {
      val whereClause = pkColumns.map(col => s"$col = ?").mkString(" AND ")
      
      s"""
         |DELETE FROM ${table.database}.${table.table}
         |WHERE $whereClause
         |""".stripMargin
    })
  }
  
  private def setParameters(stmt: PreparedStatement, values: Seq[Any]): Unit = {
    values.zipWithIndex.foreach { case (value, index) =>
      val paramIndex = index + 1
      
      value match {
        case null =>
          stmt.setNull(paramIndex, java.sql.Types.NULL)
        case v: String =>
          stmt.setString(paramIndex, v)
        case v: Int =>
          stmt.setInt(paramIndex, v)
        case v: Long =>
          stmt.setLong(paramIndex, v)
        case v: Double =>
          stmt.setDouble(paramIndex, v)
        case v: Float =>
          stmt.setFloat(paramIndex, v)
        case v: Boolean =>
          stmt.setBoolean(paramIndex, v)
        case v: java.math.BigDecimal =>
          stmt.setBigDecimal(paramIndex, v)
        case v: java.sql.Date =>
          stmt.setDate(paramIndex, v)
        case v: java.sql.Time =>
          stmt.setTime(paramIndex, v)
        case v: java.sql.Timestamp =>
          stmt.setTimestamp(paramIndex, v)
        case v: java.time.Instant =>
          stmt.setTimestamp(paramIndex, java.sql.Timestamp.from(v))
        case v: java.time.LocalDate =>
          stmt.setDate(paramIndex, java.sql.Date.valueOf(v))
        case v: java.time.LocalTime =>
          stmt.setTime(paramIndex, java.sql.Time.valueOf(v))
        case v: Array[Byte] =>
          stmt.setBytes(paramIndex, v)
        case v =>
          // 其他类型转为字符串
          stmt.setString(paramIndex, v.toString)
      }
    }
  }
  
  private def isDuplicateKeyError(ex: SQLException): Boolean = {
    // MySQL 重复键错误码：1062
    ex.getErrorCode == 1062 || ex.getMessage.contains("Duplicate entry")
  }
  
  /**
   * 清理 statement 缓存
   */
  def clearCache(): Unit = {
    statementCache.clear()
    logger.info("Cleared statement cache")
  }
  
  /**
   * 获取缓存统计信息
   */
  def getCacheStatistics(): Map[String, Int] = {
    Map(
      "cacheSize" -> statementCache.size,
      "insertStatements" -> statementCache.keys.count(_.startsWith("INSERT:")),
      "updateStatements" -> statementCache.keys.count(_.startsWith("UPDATE:")),
      "deleteStatements" -> statementCache.keys.count(_.startsWith("DELETE:"))
    )
  }
}

object IdempotentMySQLSink {
  /**
   * 创建 Idempotent MySQL Sink 实例
   */
  def apply(config: DatabaseConfig)(implicit ec: ExecutionContext): IdempotentMySQLSink = {
    new IdempotentMySQLSink(config)
  }
}
