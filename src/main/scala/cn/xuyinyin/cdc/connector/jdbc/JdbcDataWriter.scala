package cn.xuyinyin.cdc.connector.jdbc

import cn.xuyinyin.cdc.connector.{ConnectorConfig, DataWriter}
import cn.xuyinyin.cdc.model.TableId
import com.typesafe.scalalogging.LazyLogging

import java.sql.PreparedStatement
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}

/**
 * JDBC 数据写入器基类
 * 
 * 提供通用的 JDBC 写入逻辑，子类只需实现 SQL 构建方法
 */
abstract class JdbcDataWriter(
  connectionManager: JdbcConnectionManager,
  connectorConfig: ConnectorConfig
)(implicit ec: ExecutionContext) extends DataWriter with LazyLogging {
  
  // SQL 语句缓存
  private val sqlCache = scala.collection.concurrent.TrieMap[String, String]()
  
  /**
   * 构建 INSERT SQL（子类实现）
   */
  protected def buildInsertSql(table: TableId, columns: Seq[String]): String
  
  /**
   * 构建 UPDATE SQL（子类实现）
   */
  protected def buildUpdateSql(table: TableId, pkColumns: Seq[String], dataColumns: Seq[String]): String
  
  /**
   * 构建 DELETE SQL（子类实现）
   */
  protected def buildDeleteSql(table: TableId, pkColumns: Seq[String]): String
  
  /**
   * 获取目标数据库名（可选，用于跨库同步）
   */
  protected def getTargetDatabase(): Option[String] = None
  
  /**
   * 获取完整的表名
   */
  protected def getFullTableName(table: TableId): String = {
    getTargetDatabase() match {
      case Some(db) => s"$db.${table.table}"
      case None => s"${table.database}.${table.table}"
    }
  }
  
  override def insert(table: TableId, data: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(connectionManager.getConnection()) { conn =>
        val columns = data.keys.toSeq
        val sql = getCachedSql(s"INSERT:${table}:${columns.mkString(",")}", 
          buildInsertSql(table, columns))
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          setParameters(stmt, getInsertParameters(data, columns))
          stmt.executeUpdate()
        }
        
        logger.debug(s"Executed INSERT: $table")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to execute INSERT: $table", ex)
        throw ex
    }
  }
  
  override def update(table: TableId, primaryKey: Map[String, Any], data: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(connectionManager.getConnection()) { conn =>
        val pkColumns = primaryKey.keys.toSeq
        val dataColumns = data.keys.toSeq
        val sql = getCachedSql(
          s"UPDATE:${table}:${pkColumns.mkString(",")}:${dataColumns.mkString(",")}",
          buildUpdateSql(table, pkColumns, dataColumns)
        )
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          setParameters(stmt, data.values.toSeq ++ primaryKey.values.toSeq)
          stmt.executeUpdate()
        }
        
        logger.debug(s"Executed UPDATE: $table")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to execute UPDATE: $table", ex)
        throw ex
    }
  }
  
  override def delete(table: TableId, primaryKey: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(connectionManager.getConnection()) { conn =>
        val pkColumns = primaryKey.keys.toSeq
        val sql = getCachedSql(
          s"DELETE:${table}:${pkColumns.mkString(",")}",
          buildDeleteSql(table, pkColumns)
        )
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          setParameters(stmt, primaryKey.values.toSeq)
          stmt.executeUpdate()
        }
        
        logger.debug(s"Executed DELETE: $table")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to execute DELETE: $table", ex)
        throw ex
    }
  }
  
  override def batchInsert(table: TableId, rows: Seq[Map[String, Any]]): Future[Unit] = Future {
    if (rows.isEmpty) return Future.successful(())
    
    Try {
      Using.resource(connectionManager.getConnection()) { conn =>
        conn.setAutoCommit(false)
        
        try {
          val columns = rows.head.keys.toSeq
          val sql = getCachedSql(s"INSERT:${table}:${columns.mkString(",")}", 
            buildInsertSql(table, columns))
          
          Using.resource(conn.prepareStatement(sql)) { stmt =>
            rows.foreach { row =>
              setParameters(stmt, getInsertParameters(row, columns))
              stmt.addBatch()
            }
            stmt.executeBatch()
          }
          
          conn.commit()
          logger.info(s"Executed batch INSERT: $table, count: ${rows.size}")
        } catch {
          case ex: Exception =>
            conn.rollback()
            throw ex
        } finally {
          conn.setAutoCommit(true)
        }
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        logger.error(s"Failed to execute batch INSERT: $table", ex)
        throw ex
    }
  }
  
  override def close(): Unit = {
    connectionManager.close()
  }
  
  /**
   * 获取 INSERT 操作的参数（子类可以重写以支持 UPSERT）
   */
  protected def getInsertParameters(data: Map[String, Any], columns: Seq[String]): Seq[Any] = {
    columns.map(data)
  }
  
  /**
   * 设置 PreparedStatement 参数
   */
  protected def setParameters(stmt: PreparedStatement, values: Seq[Any]): Unit = {
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
          stmt.setString(paramIndex, v.toString)
      }
    }
  }
  
  /**
   * 获取缓存的 SQL
   */
  private def getCachedSql(key: String, sqlBuilder: => String): String = {
    sqlCache.getOrElseUpdate(key, sqlBuilder)
  }
}
