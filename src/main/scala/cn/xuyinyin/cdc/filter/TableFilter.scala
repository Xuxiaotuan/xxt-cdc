package cn.xuyinyin.cdc.filter

import cn.xuyinyin.cdc.config.FilterConfig
import cn.xuyinyin.cdc.model.TableId
import com.typesafe.scalalogging.LazyLogging

import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

/**
 * 表过滤器
 * 支持基于库名、表名的包含/排除规则，支持通配符和正则表达式
 * 
 * @param config 过滤配置
 */
class TableFilter(config: FilterConfig) extends LazyLogging {
  
  // 编译正则表达式模式以提高性能
  private val includeTableRegexes: Seq[Regex] = compilePatterns(config.includeTablePatterns)
  private val excludeTableRegexes: Seq[Regex] = compilePatterns(config.excludeTablePatterns)
  
  private def compilePatterns(patterns: Seq[String]): Seq[Regex] = {
    patterns.flatMap { pattern =>
      Try {
        if (pattern.contains("*") || pattern.contains("?")) {
          // 通配符模式转换为正则表达式
          val regexPattern = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
          ("^" + regexPattern + "$").r
        } else {
          // 直接作为正则表达式
          pattern.r
        }
      } match {
        case Success(regex) => Some(regex)
        case Failure(ex) =>
          logger.warn(s"Invalid pattern '$pattern': ${ex.getMessage}")
          None
      }
    }
  }
  
  /**
   * 检查表是否应该被包含
   * 
   * @param table 表标识
   * @return 是否应该包含该表
   */
  def shouldInclude(table: TableId): Boolean = {
    // 1. 检查数据库级别的过滤
    if (!isDatabaseIncluded(table.database)) {
      logger.debug(s"Database ${table.database} is excluded")
      return false
    }
    
    // 2. 检查表级别的过滤
    if (!isTableIncluded(table.table)) {
      logger.debug(s"Table ${table.table} is excluded")
      return false
    }
    
    logger.debug(s"Table $table is included")
    true
  }
  
  private def isDatabaseIncluded(database: String): Boolean = {
    // 检查数据库包含规则
    val included = config.includeDatabases.isEmpty || 
      config.includeDatabases.contains(database)
    
    // 检查数据库排除规则
    val excluded = config.excludeDatabases.contains(database)
    
    included && !excluded
  }
  
  private def isTableIncluded(tableName: String): Boolean = {
    // 检查表包含规则
    val included = config.includeTablePatterns.isEmpty ||
      includeTableRegexes.exists(_.findFirstIn(tableName).isDefined)
    
    // 检查表排除规则
    val excluded = excludeTableRegexes.exists(_.findFirstIn(tableName).isDefined)
    
    included && !excluded
  }
  
  /**
   * 批量过滤表列表
   * 
   * @param tables 表列表
   * @return 过滤后的表列表
   */
  def filterTables(tables: Seq[TableId]): Seq[TableId] = {
    val filtered = tables.filter(shouldInclude)
    logger.info(s"Filtered ${tables.size} tables to ${filtered.size} tables")
    filtered
  }
  
  /**
   * 获取过滤统计信息
   * 
   * @param tables 原始表列表
   * @return 过滤统计
   */
  def getFilterStatistics(tables: Seq[TableId]): FilterStatistics = {
    val totalCount = tables.size
    val includedTables = tables.filter(shouldInclude)
    val includedCount = includedTables.size
    val excludedCount = totalCount - includedCount
    
    // 按数据库分组统计
    val databaseStats = tables.groupBy(_.database).map { case (db, dbTables) =>
      val dbIncluded = dbTables.filter(shouldInclude)
      db -> DatabaseFilterStats(
        totalTables = dbTables.size,
        includedTables = dbIncluded.size,
        excludedTables = dbTables.size - dbIncluded.size
      )
    }
    
    FilterStatistics(
      totalTables = totalCount,
      includedTables = includedCount,
      excludedTables = excludedCount,
      databaseStats = databaseStats
    )
  }
  
  /**
   * 验证过滤配置
   * 
   * @return 验证结果
   */
  def validateConfig(): FilterValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val warnings = scala.collection.mutable.ListBuffer[String]()
    
    // 检查是否有冲突的配置
    if (config.includeDatabases.nonEmpty && config.excludeDatabases.nonEmpty) {
      val conflicts = config.includeDatabases.intersect(config.excludeDatabases)
      if (conflicts.nonEmpty) {
        errors += s"Conflicting database rules: ${conflicts.mkString(", ")} appear in both include and exclude lists"
      }
    }
    
    // 检查模式是否有效
    config.includeTablePatterns.foreach { pattern =>
      Try(compilePatterns(Seq(pattern))) match {
        case Failure(ex) =>
          errors += s"Invalid include pattern '$pattern': ${ex.getMessage}"
        case _ =>
      }
    }
    
    config.excludeTablePatterns.foreach { pattern =>
      Try(compilePatterns(Seq(pattern))) match {
        case Failure(ex) =>
          errors += s"Invalid exclude pattern '$pattern': ${ex.getMessage}"
        case _ =>
      }
    }
    
    // 检查是否过于宽泛的配置
    if (config.includeDatabases.isEmpty && config.includeTablePatterns.isEmpty) {
      warnings += "No include rules specified, all tables will be included by default"
    }
    
    FilterValidationResult(
      isValid = errors.isEmpty,
      errors = errors.toSeq,
      warnings = warnings.toSeq
    )
  }
}

/**
 * 过滤统计信息
 */
case class FilterStatistics(
  totalTables: Int,
  includedTables: Int,
  excludedTables: Int,
  databaseStats: Map[String, DatabaseFilterStats]
) {
  def inclusionRate: Double = {
    if (totalTables > 0) includedTables.toDouble / totalTables else 0.0
  }
  
  def toMap: Map[String, Any] = Map(
    "totalTables" -> totalTables,
    "includedTables" -> includedTables,
    "excludedTables" -> excludedTables,
    "inclusionRate" -> f"${inclusionRate * 100}%.2f%%",
    "databaseStats" -> databaseStats.map { case (db, stats) =>
      db -> Map(
        "totalTables" -> stats.totalTables,
        "includedTables" -> stats.includedTables,
        "excludedTables" -> stats.excludedTables
      )
    }
  )
}

/**
 * 数据库级别的过滤统计
 */
case class DatabaseFilterStats(
  totalTables: Int,
  includedTables: Int,
  excludedTables: Int
)

/**
 * 过滤配置验证结果
 */
case class FilterValidationResult(
  isValid: Boolean,
  errors: Seq[String],
  warnings: Seq[String]
)

object TableFilter {
  /**
   * 创建 Table Filter 实例
   */
  def apply(config: FilterConfig): TableFilter = new TableFilter(config)
  
  /**
   * 创建默认的过滤器（排除系统数据库）
   */
  def createDefault(): TableFilter = {
    val defaultConfig = FilterConfig(
      excludeDatabases = Seq("information_schema", "mysql", "performance_schema", "sys")
    )
    new TableFilter(defaultConfig)
  }
}