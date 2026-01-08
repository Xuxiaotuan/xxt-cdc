package cn.xuyinyin.cdc.sink

import cn.xuyinyin.cdc.config.DatabaseConfig
import cn.xuyinyin.cdc.model.TableId
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.{Connection, PreparedStatement}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}

/**
 * 带连接池的 MySQL Sink 实现
 * 使用 HikariCP 连接池提高性能
 * 
 * @param config 目标数据库配置
 */
class PooledMySQLSink(config: DatabaseConfig)(implicit ec: ExecutionContext) 
  extends MySQLSink with LazyLogging {

  // 初始化 HikariCP 连接池
  private val dataSource: HikariDataSource = initializeDataSource()
  
  // Statement 缓存
  private val statementCache = scala.collection.concurrent.TrieMap[String, String]()
  
  private def initializeDataSource(): HikariDataSource = {
    val hikariConfig = new HikariConfig()
    
    hikariConfig.setJdbcUrl(
      s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
      "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
    )
    hikariConfig.setUsername(config.username)
    hikariConfig.setPassword(config.password)
    
    // 连接池配置
    hikariConfig.setMaximumPoolSize(config.connectionPool.maxPoolSize)
    hikariConfig.setMinimumIdle(config.connectionPool.minIdle)
    hikariConfig.setConnectionTimeout(config.connectionPool.connectionTimeout.toMillis)
    
    // 性能优化配置
    hikariConfig.setAutoCommit(true)
    hikariConfig.addDataSourceProperty("cachePrepStmts", "true")
    hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250")
    hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    hikariConfig.addDataSourceProperty("useServerPrepStmts", "true")
    
    // 连接测试
    hikariConfig.setConnectionTestQuery("SELECT 1")
    
    val ds = new HikariDataSource(hikariConfig)
    logger.info(s"Initialized HikariCP connection pool: max=${config.connectionPool.maxPoolSize}, min=${config.connectionPool.minIdle}")
    ds
  }
  
  private def getConnection(): Connection = {
    dataSource.getConnection()
  }
  
  override def executeInsert(table: TableId, data: Map[String, Any]): Future[Unit] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val sql = getOrCreateInsertStatement(table, data.keys.toSeq)
        
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          setParameters(stmt, data.values.toSeq ++ data.values.toSeq)
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
          setParameters(stmt, data.values.toSeq ++ pk.values.toSeq)
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
          stmt.setString(paramIndex, v.toString)
      }
    }
  }
  
  /**
   * 批量执行插入操作
   */
  def executeBatchInsert(table: TableId, dataList: Seq[Map[String, Any]]): Future[Unit] = Future {
    if (dataList.isEmpty) return Future.successful(())
    
    Try {
      Using.resource(getConnection()) { conn =>
        conn.setAutoCommit(false)
        
        try {
          val columns = dataList.head.keys.toSeq
          val sql = getOrCreateInsertStatement(table, columns)
          
          Using.resource(conn.prepareStatement(sql)) { stmt =>
            dataList.foreach { data =>
              setParameters(stmt, data.values.toSeq ++ data.values.toSeq)
              stmt.addBatch()
            }
            
            stmt.executeBatch()
          }
          
          conn.commit()
          logger.debug(s"Executed batch INSERT: $table, count: ${dataList.size}")
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
  
  /**
   * 关闭连接池
   */
  def close(): Unit = {
    if (!dataSource.isClosed) {
      dataSource.close()
      logger.info("Closed HikariCP connection pool")
    }
  }
  
  /**
   * 获取连接池统计信息
   */
  def getPoolStatistics(): Map[String, Any] = {
    Map(
      "activeConnections" -> dataSource.getHikariPoolMXBean.getActiveConnections,
      "idleConnections" -> dataSource.getHikariPoolMXBean.getIdleConnections,
      "totalConnections" -> dataSource.getHikariPoolMXBean.getTotalConnections,
      "threadsAwaitingConnection" -> dataSource.getHikariPoolMXBean.getThreadsAwaitingConnection
    )
  }
}

object PooledMySQLSink {
  /**
   * 创建 Pooled MySQL Sink 实例
   */
  def apply(config: DatabaseConfig)(implicit ec: ExecutionContext): PooledMySQLSink = {
    new PooledMySQLSink(config)
  }
}
