package cn.xuyinyin.cdc.catalog

import cn.xuyinyin.cdc.config.{DatabaseConfig, FilterConfig}
import cn.xuyinyin.cdc.model._
import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager, ResultSet}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try, Using}
import scala.util.matching.Regex

/**
 * MySQL Catalog Service 实现
 * 负责表发现、元数据管理和过滤规则处理
 * 
 * @param config 数据库配置
 */
class MySQLCatalogService(config: DatabaseConfig)(implicit ec: ExecutionContext) 
  extends CatalogService with LazyLogging {

  private val jdbcUrl = s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
    "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
  
  private def getConnection(): Connection = {
    DriverManager.getConnection(jdbcUrl, config.username, config.password)
  }
  
  override def discoverTables(filterConfig: FilterConfig): Future[Seq[TableMeta]] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val tables = getAllTables(conn)
        
        // 应用过滤规则
        val filteredTables = tables.filter { table =>
          matchesFilter(table, filterConfig)
        }
        
        // 获取每个表的详细元数据
        filteredTables.flatMap { table =>
          getTableMetadata(conn, table)
        }
      }
    } match {
      case Success(tables) =>
        logger.info(s"Discovered ${tables.size} tables")
        tables
      case Failure(ex) =>
        logger.error(s"Failed to discover tables: ${ex.getMessage}", ex)
        Seq.empty
    }
  }
  
  private def getAllTables(conn: Connection): Seq[TableId] = {
    val sql = """
      SELECT TABLE_SCHEMA, TABLE_NAME
      FROM information_schema.TABLES
      WHERE TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
        AND TABLE_TYPE = 'BASE TABLE'
      ORDER BY TABLE_SCHEMA, TABLE_NAME
    """
    
    Using.resource(conn.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery(sql)) { rs =>
        Iterator.continually(rs)
          .takeWhile(_.next())
          .map { rs =>
            TableId(
              database = rs.getString("TABLE_SCHEMA"),
              table = rs.getString("TABLE_NAME")
            )
          }
          .toSeq
      }
    }
  }
  
  private def matchesFilter(table: TableId, filter: FilterConfig): Boolean = {
    // 检查数据库包含/排除规则
    val databaseMatches = {
      val included = filter.includeDatabases.isEmpty || 
        filter.includeDatabases.contains(table.database)
      val excluded = filter.excludeDatabases.contains(table.database)
      included && !excluded
    }
    
    if (!databaseMatches) return false
    
    // 检查表名模式匹配
    val tableMatches = {
      val included = filter.includeTablePatterns.isEmpty ||
        filter.includeTablePatterns.exists(pattern => matchesPattern(table.table, pattern))
      val excluded = filter.excludeTablePatterns.exists(pattern => matchesPattern(table.table, pattern))
      included && !excluded
    }
    
    tableMatches
  }
  
  private def matchesPattern(tableName: String, pattern: String): Boolean = {
    // 支持简单的通配符模式和正则表达式
    if (pattern.contains("*") || pattern.contains("?")) {
      // 通配符模式
      val regexPattern = pattern
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
      tableName.matches(regexPattern)
    } else {
      // 尝试作为正则表达式
      Try(tableName.matches(pattern)).getOrElse(false)
    }
  }
  
  private def getTableMetadata(conn: Connection, tableId: TableId): Option[TableMeta] = {
    Try {
      val columns = getTableColumns(conn, tableId)
      val primaryKeys = getTablePrimaryKeys(conn, tableId)
      val estimatedRows = getTableRowCount(conn, tableId)
      
      TableMeta(
        tableId = tableId,
        primaryKeys = primaryKeys,
        columns = columns,
        estimatedRows = estimatedRows,
        binlogEnabled = true // 假设所有表都启用了 binlog
      )
    } match {
      case Success(meta) => Some(meta)
      case Failure(ex) =>
        logger.error(s"Failed to get metadata for table $tableId: ${ex.getMessage}", ex)
        None
    }
  }
  
  private def getTableColumns(conn: Connection, tableId: TableId): Seq[ColumnMeta] = {
    val sql = """
      SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, 
             CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE
      FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
      ORDER BY ORDINAL_POSITION
    """
    
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      stmt.setString(1, tableId.database)
      stmt.setString(2, tableId.table)
      
      Using.resource(stmt.executeQuery()) { rs =>
        Iterator.continually(rs)
          .takeWhile(_.next())
          .map { rs =>
            val columnName = rs.getString("COLUMN_NAME")
            val dataType = rs.getString("DATA_TYPE")
            val isNullable = rs.getString("IS_NULLABLE") == "YES"
            val defaultValue = Option(rs.getString("COLUMN_DEFAULT"))
            
            // 使用 getLong 避免 UNSIGNED INT 溢出问题
            val charLength = Option(rs.getLong("CHARACTER_MAXIMUM_LENGTH")).filter(_ > 0).map(_.toInt)
            val numericPrecision = Option(rs.getLong("NUMERIC_PRECISION")).filter(_ > 0).map(_.toInt)
            val numericScale = Option(rs.getLong("NUMERIC_SCALE")).filter(_ > 0).map(_.toInt)
            
            ColumnMeta(
              name = columnName,
              dataType = parseMySQLDataType(dataType, charLength, numericPrecision, numericScale),
              nullable = isNullable,
              defaultValue = defaultValue
            )
          }
          .toSeq
      }
    }
  }
  
  private def parseMySQLDataType(
    dataType: String,
    charLength: Option[Int],
    precision: Option[Int],
    scale: Option[Int]
  ): MySQLDataType = {
    dataType.toLowerCase match {
      case "tinyint" => TinyInt
      case "smallint" => SmallInt
      case "mediumint" => MediumInt
      case "int" | "integer" => Int
      case "bigint" => BigInt
      case "decimal" | "numeric" => 
        Decimal(precision.getOrElse(10), scale.getOrElse(0))
      case "float" => Float
      case "double" | "real" => Double
      case "varchar" => VarChar(charLength.getOrElse(255))
      case "char" => Char(charLength.getOrElse(1))
      case "text" | "tinytext" | "mediumtext" => Text
      case "longtext" => LongText
      case "datetime" => DateTime
      case "timestamp" => Timestamp
      case "date" => Date
      case "time" => Time
      case "json" => Json
      case "blob" | "tinyblob" | "mediumblob" | "longblob" | "binary" | "varbinary" => Blob
      case _ => 
        logger.warn(s"Unknown data type: $dataType, treating as Text")
        Text
    }
  }
  
  private def getTablePrimaryKeys(conn: Connection, tableId: TableId): Seq[String] = {
    val sql = """
      SELECT COLUMN_NAME
      FROM information_schema.KEY_COLUMN_USAGE
      WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY'
      ORDER BY ORDINAL_POSITION
    """
    
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      stmt.setString(1, tableId.database)
      stmt.setString(2, tableId.table)
      
      Using.resource(stmt.executeQuery()) { rs =>
        Iterator.continually(rs)
          .takeWhile(_.next())
          .map(_.getString("COLUMN_NAME"))
          .toSeq
      }
    }
  }
  
  private def getTableRowCount(conn: Connection, tableId: TableId): Long = {
    val sql = """
      SELECT TABLE_ROWS
      FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
    """
    
    Try {
      Using.resource(conn.prepareStatement(sql)) { stmt =>
        stmt.setString(1, tableId.database)
        stmt.setString(2, tableId.table)
        
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) {
            rs.getLong("TABLE_ROWS")
          } else {
            0L
          }
        }
      }
    }.getOrElse(0L)
  }
  
  override def getTableSchema(table: TableId): Future[TableSchema] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val columns = getTableColumns(conn, table)
        val primaryKeys = getTablePrimaryKeys(conn, table)
        val indexes = getTableIndexes(conn, table)
        
        TableSchema(
          tableId = table,
          columns = columns,
          primaryKeys = primaryKeys,
          indexes = indexes
        )
      }
    } match {
      case Success(schema) => schema
      case Failure(ex) =>
        logger.error(s"Failed to get schema for table $table: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  private def getTableIndexes(conn: Connection, tableId: TableId): Seq[IndexMeta] = {
    val sql = """
      SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE
      FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
      ORDER BY INDEX_NAME, SEQ_IN_INDEX
    """
    
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      stmt.setString(1, tableId.database)
      stmt.setString(2, tableId.table)
      
      Using.resource(stmt.executeQuery()) { rs =>
        val indexMap = scala.collection.mutable.Map[String, (Boolean, Seq[String])]()
        
        while (rs.next()) {
          val indexName = rs.getString("INDEX_NAME")
          val columnName = rs.getString("COLUMN_NAME")
          val nonUnique = rs.getInt("NON_UNIQUE") == 1
          
          if (indexName != "PRIMARY") {
            val (isUnique, columns) = indexMap.getOrElse(indexName, (!nonUnique, Seq.empty))
            indexMap(indexName) = (isUnique, columns :+ columnName)
          }
        }
        
        indexMap.map { case (name, (unique, columns)) =>
          IndexMeta(name, columns, unique)
        }.toSeq
      }
    }
  }
  
  override def validateBinlogConfig(source: DatabaseConfig): Future[BinlogCapability] = Future {
    Try {
      Using.resource(getConnection()) { conn =>
        val binlogEnabled = checkBinlogEnabled(conn)
        val binlogFormat = getBinlogFormat(conn)
        val binlogRowImage = getBinlogRowImage(conn)
        val gtidEnabled = checkGTIDEnabled(conn)
        
        BinlogCapability(
          enabled = binlogEnabled,
          format = binlogFormat,
          rowImage = binlogRowImage,
          gtidEnabled = gtidEnabled
        )
      }
    } match {
      case Success(capability) =>
        logger.info(s"Binlog capability: $capability")
        
        // 验证配置是否满足要求
        if (!capability.enabled) {
          logger.error("Binlog is not enabled!")
        }
        if (capability.format != "ROW") {
          logger.warn(s"Binlog format is ${capability.format}, ROW format is recommended")
        }
        
        capability
      case Failure(ex) =>
        logger.error(s"Failed to validate binlog config: ${ex.getMessage}", ex)
        throw ex
    }
  }
  
  private def checkBinlogEnabled(conn: Connection): Boolean = {
    val sql = "SHOW VARIABLES LIKE 'log_bin'"
    
    Using.resource(conn.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery(sql)) { rs =>
        if (rs.next()) {
          rs.getString("Value").equalsIgnoreCase("ON")
        } else {
          false
        }
      }
    }
  }
  
  private def getBinlogFormat(conn: Connection): String = {
    val sql = "SHOW VARIABLES LIKE 'binlog_format'"
    
    Using.resource(conn.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery(sql)) { rs =>
        if (rs.next()) {
          rs.getString("Value")
        } else {
          "UNKNOWN"
        }
      }
    }
  }
  
  private def getBinlogRowImage(conn: Connection): String = {
    val sql = "SHOW VARIABLES LIKE 'binlog_row_image'"
    
    Using.resource(conn.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery(sql)) { rs =>
        if (rs.next()) {
          rs.getString("Value")
        } else {
          "FULL" // 默认值
        }
      }
    }
  }
  
  private def checkGTIDEnabled(conn: Connection): Boolean = {
    val sql = "SHOW VARIABLES LIKE 'gtid_mode'"
    
    Using.resource(conn.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery(sql)) { rs =>
        if (rs.next()) {
          rs.getString("Value").equalsIgnoreCase("ON")
        } else {
          false
        }
      }
    }
  }
}

object MySQLCatalogService {
  /**
   * 创建 MySQL Catalog Service 实例
   */
  def apply(config: DatabaseConfig)(implicit ec: ExecutionContext): MySQLCatalogService = {
    new MySQLCatalogService(config)
  }
}
