package cn.xuyinyin.cdc.config

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * CDC 配置加载器
 * 从配置文件加载和验证 CDC 配置
 */
object ConfigLoader extends LazyLogging {
  
  // 辅助方法：将 Java Duration 转换为 Scala Duration
  private def toScalaDuration(javaDuration: java.time.Duration): FiniteDuration = {
    FiniteDuration(javaDuration.toMillis, MILLISECONDS)
  }
  
  /**
   * 从默认配置文件加载 CDC 配置
   */
  def load(): CDCConfig = {
    val config = ConfigFactory.load()
    loadFromConfig(config)
  }
  
  /**
   * 从指定配置文件加载 CDC 配置
   */
  def loadFromFile(configFile: String): CDCConfig = {
    val config = ConfigFactory.parseFile(new java.io.File(configFile))
      .withFallback(ConfigFactory.load())
    loadFromConfig(config)
  }
  
  /**
   * 从 Config 对象加载 CDC 配置
   */
  def loadFromConfig(config: Config): CDCConfig = {
    logger.info("Loading CDC configuration")
    
    try {
      val cdcConfig = config.getConfig("cdc")
      
      val sourceConfig = loadDatabaseConfig(cdcConfig.getConfig("source"))
      val targetConfig = loadDatabaseConfig(cdcConfig.getConfig("target"))
      val filterConfig = loadFilterConfig(cdcConfig.getConfig("filter"))
      val parallelismConfig = loadParallelismConfig(cdcConfig.getConfig("parallelism"))
      val offsetConfig = loadOffsetConfig(cdcConfig.getConfig("offset"))
      
      val result = CDCConfig(
        source = sourceConfig,
        target = targetConfig,
        filter = filterConfig,
        parallelism = parallelismConfig,
        offset = offsetConfig
      )
      
      logger.info("CDC configuration loaded successfully")
      result
      
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to load CDC configuration: ${ex.getMessage}", ex)
        throw new IllegalArgumentException(s"Invalid CDC configuration: ${ex.getMessage}", ex)
    }
  }
  
  private def loadDatabaseConfig(config: Config): DatabaseConfig = {
    DatabaseConfig(
      host = config.getString("host"),
      port = config.getInt("port"),
      username = config.getString("username"),
      password = config.getString("password"),
      database = config.getString("database"),
      connectionPool = ConnectionPoolConfig(
        maxPoolSize = config.getInt("connection-pool.max-pool-size"),
        minIdle = config.getInt("connection-pool.min-idle"),
        connectionTimeout = toScalaDuration(config.getDuration("connection-pool.connection-timeout"))
      )
    )
  }
  
  private def loadFilterConfig(config: Config): FilterConfig = {
    FilterConfig(
      includeDatabases = getStringList(config, "include-databases"),
      excludeDatabases = getStringList(config, "exclude-databases"),
      includeTablePatterns = getStringList(config, "include-table-patterns"),
      excludeTablePatterns = getStringList(config, "exclude-table-patterns")
    )
  }
  
  private def loadParallelismConfig(config: Config): ParallelismConfig = {
    ParallelismConfig(
      partitionCount = config.getInt("partition-count"),
      applyWorkerCount = config.getInt("apply-worker-count"),
      snapshotWorkerCount = config.getInt("snapshot-worker-count"),
      batchSize = config.getInt("batch-size"),
      flushInterval = toScalaDuration(config.getDuration("flush-interval"))
    )
  }
  
  private def loadOffsetConfig(config: Config): OffsetConfig = {
    val storeType = config.getString("store-type") match {
      case "mysql" => MySQLOffsetStore
      case "file" => FileOffsetStore
      case other => throw new IllegalArgumentException(s"Unknown offset store type: $other")
    }
    
    val storeConfig = storeType match {
      case MySQLOffsetStore =>
        Map("table-name" -> config.getString("mysql.table-name"))
      case FileOffsetStore =>
        Map("path" -> config.getString("file.path"))
    }
    
    val startFromLatest = Try(config.getBoolean("start-from-latest")).getOrElse(false)
    val enableSnapshot = Try(config.getBoolean("enable-snapshot")).getOrElse(false)
    
    OffsetConfig(
      storeType = storeType,
      commitInterval = toScalaDuration(config.getDuration("commit-interval")),
      storeConfig = storeConfig,
      startFromLatest = startFromLatest,
      enableSnapshot = enableSnapshot
    )
  }
  
  private def getStringList(config: Config, path: String): Seq[String] = {
    Try(config.getStringList(path).asScala.toSeq).getOrElse(Seq.empty)
  }
}

/**
 * CDC 配置验证器
 */
object ConfigValidator extends LazyLogging {
  
  /**
   * 验证 CDC 配置
   */
  def validate(config: CDCConfig): ConfigValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val warnings = scala.collection.mutable.ListBuffer[String]()
    
    // 验证数据库配置
    validateDatabaseConfig("source", config.source, errors, warnings)
    validateDatabaseConfig("target", config.target, errors, warnings)
    
    // 验证并行度配置
    validateParallelismConfig(config.parallelism, errors, warnings)
    
    // 验证偏移量配置
    validateOffsetConfig(config.offset, errors, warnings)
    
    // 验证过滤配置
    validateFilterConfig(config.filter, errors, warnings)
    
    // 验证配置一致性
    validateConfigConsistency(config, errors, warnings)
    
    ConfigValidationResult(
      isValid = errors.isEmpty,
      errors = errors.toSeq,
      warnings = warnings.toSeq
    )
  }
  
  private def validateDatabaseConfig(
    name: String,
    config: DatabaseConfig,
    errors: scala.collection.mutable.ListBuffer[String],
    warnings: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    if (config.host.isEmpty) {
      errors += s"$name database host cannot be empty"
    }
    
    if (config.port <= 0 || config.port > 65535) {
      errors += s"$name database port must be between 1 and 65535"
    }
    
    if (config.username.isEmpty) {
      errors += s"$name database username cannot be empty"
    }
    
    if (config.database.isEmpty) {
      errors += s"$name database name cannot be empty"
    }
    
    if (config.connectionPool.maxPoolSize <= 0) {
      errors += s"$name database max pool size must be positive"
    }
    
    if (config.connectionPool.minIdle < 0) {
      errors += s"$name database min idle cannot be negative"
    }
    
    if (config.connectionPool.minIdle > config.connectionPool.maxPoolSize) {
      errors += s"$name database min idle cannot be greater than max pool size"
    }
    
    if (config.connectionPool.connectionTimeout.toSeconds <= 0) {
      errors += s"$name database connection timeout must be positive"
    }
  }
  
  private def validateParallelismConfig(
    config: ParallelismConfig,
    errors: scala.collection.mutable.ListBuffer[String],
    warnings: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    if (config.partitionCount <= 0) {
      errors += "Partition count must be positive"
    }
    
    if (config.applyWorkerCount <= 0) {
      errors += "Apply worker count must be positive"
    }
    
    if (config.snapshotWorkerCount <= 0) {
      errors += "Snapshot worker count must be positive"
    }
    
    if (config.batchSize <= 0) {
      errors += "Batch size must be positive"
    }
    
    if (config.flushInterval.toMillis <= 0) {
      errors += "Flush interval must be positive"
    }
    
    // 性能建议
    if (config.partitionCount > 256) {
      warnings += "Very high partition count may impact performance"
    }
    
    if (config.batchSize > 10000) {
      warnings += "Very large batch size may cause memory issues"
    }
    
    if (config.flushInterval.toSeconds > 60) {
      warnings += "Very long flush interval may increase data loss risk"
    }
  }
  
  private def validateOffsetConfig(
    config: OffsetConfig,
    errors: scala.collection.mutable.ListBuffer[String],
    warnings: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    if (config.commitInterval.toSeconds <= 0) {
      errors += "Commit interval must be positive"
    }
    
    config.storeType match {
      case MySQLOffsetStore =>
        if (!config.storeConfig.contains("table-name") || config.storeConfig("table-name").isEmpty) {
          errors += "MySQL offset store requires table-name configuration"
        }
      case FileOffsetStore =>
        if (!config.storeConfig.contains("path") || config.storeConfig("path").isEmpty) {
          errors += "File offset store requires path configuration"
        }
    }
    
    if (config.commitInterval.toSeconds > 300) {
      warnings += "Very long commit interval may increase data loss risk"
    }
  }
  
  private def validateFilterConfig(
    config: FilterConfig,
    errors: scala.collection.mutable.ListBuffer[String],
    warnings: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    // 检查冲突的数据库规则
    val conflictingDatabases = config.includeDatabases.intersect(config.excludeDatabases)
    if (conflictingDatabases.nonEmpty) {
      errors += s"Conflicting database rules: ${conflictingDatabases.mkString(", ")}"
    }
    
    // 验证正则表达式模式
    (config.includeTablePatterns ++ config.excludeTablePatterns).foreach { pattern =>
      Try {
        pattern.r
      } match {
        case Failure(_) =>
          // 尝试作为通配符模式
          if (!pattern.matches("[a-zA-Z0-9_*?.-]+")) {
            errors += s"Invalid table pattern: $pattern"
          }
        case Success(_) =>
      }
    }
    
    if (config.includeDatabases.isEmpty && config.includeTablePatterns.isEmpty) {
      warnings += "No include rules specified, all tables will be processed"
    }
  }
  
  private def validateConfigConsistency(
    config: CDCConfig,
    errors: scala.collection.mutable.ListBuffer[String],
    warnings: scala.collection.mutable.ListBuffer[String]
  ): Unit = {
    // 检查源和目标数据库是否相同
    if (config.source.host == config.target.host &&
        config.source.port == config.target.port &&
        config.source.database == config.target.database) {
      errors += "Source and target databases cannot be the same"
    }
    
    // 检查连接池配置是否合理
    val totalConnections = config.source.connectionPool.maxPoolSize + config.target.connectionPool.maxPoolSize
    if (totalConnections > 100) {
      warnings += s"Total connection pool size ($totalConnections) is very large"
    }
  }
}

/**
 * 配置验证结果
 */
case class ConfigValidationResult(
  isValid: Boolean,
  errors: Seq[String],
  warnings: Seq[String]
) {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  
  def logResults(): Unit = {
    if (isValid) {
      logger.info("Configuration validation passed")
    } else {
      logger.error(s"Configuration validation failed: ${errors.mkString(", ")}")
    }
    
    warnings.foreach { warning =>
      logger.warn(s"Configuration warning: $warning")
    }
  }
}