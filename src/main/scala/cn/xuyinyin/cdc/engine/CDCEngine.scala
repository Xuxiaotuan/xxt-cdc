package cn.xuyinyin.cdc.engine

import cn.xuyinyin.cdc.catalog.{CatalogService, MySQLCatalogService}
import cn.xuyinyin.cdc.config.CDCConfig
import cn.xuyinyin.cdc.coordinator.{DefaultOffsetCoordinator, FileOffsetStore, MySQLOffsetStore, OffsetCoordinator, OffsetStore}
import cn.xuyinyin.cdc.filter.TableFilter
import cn.xuyinyin.cdc.health.HealthCheck
import cn.xuyinyin.cdc.metrics.CDCMetrics
import cn.xuyinyin.cdc.model._
import cn.xuyinyin.cdc.normalizer.{EventNormalizer, MySQLEventNormalizer}
import cn.xuyinyin.cdc.pipeline.CDCStreamPipeline
import cn.xuyinyin.cdc.reader.{BinlogReader, MySQLBinlogReader}
import cn.xuyinyin.cdc.router.{EventRouter, HashBasedRouter}
import cn.xuyinyin.cdc.sink.{MySQLSink, PooledMySQLSink}
import cn.xuyinyin.cdc.worker.{ApplyWorker, DefaultApplyWorker}
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.stream.Materializer
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * CDC 引擎主控制器
 * 
 * 负责管理 CDC 系统的完整生命周期，包括：
 * - INIT: 初始化所有组件
 * - SNAPSHOT: 执行全量快照（当前简化实现）
 * - CATCHUP: 追赶快照期间的变更（当前简化实现）
 * - STREAMING: 实时处理 binlog 事件
 * 
 * 使用 Pekko Streams 模式管理状态转换和组件初始化，提供：
 * - 声明式的阶段定义
 * - 清晰的日志和监控
 * - 优雅的错误处理
 * - 易于扩展的架构
 * 
 * @param config CDC 配置，包含源/目标数据库、过滤规则、并行度等
 * @param mat Pekko Streams Materializer，用于运行流处理
 * @param ec 执行上下文，用于异步操作
 */
class CDCEngine(config: CDCConfig)(implicit mat: Materializer, ec: ExecutionContext) extends LazyLogging {
  
  // ========== 状态管理 ==========
  
  /** 当前 CDC 状态，使用 AtomicReference 保证线程安全 */
  private val currentState = new AtomicReference[CDCState](Init)
  
  /** 引擎是否正在运行 */
  @volatile private var isRunning = false
  
  /** 关闭信号，用于等待引擎完全停止 */
  private val shutdownPromise = Promise[Done]()
  
  // ========== 核心组件 ==========
  
  /** 目录服务：管理表元数据、验证 binlog 配置 */
  private var catalogService: Option[CatalogService] = None
  
  /** 表过滤器：根据配置过滤需要同步的表 */
  private var tableFilter: Option[TableFilter] = None
  
  /** Binlog 读取器：从 MySQL 读取 binlog 事件 */
  private var binlogReader: Option[BinlogReader] = None
  
  /** 事件标准化器：将 binlog 事件转换为标准格式 */
  private var eventNormalizer: Option[EventNormalizer] = None
  
  /** 事件路由器：根据表和主键将事件路由到不同分区 */
  private var eventRouter: Option[EventRouter] = None
  
  /** 应用工作器：并行处理事件并应用到目标数据库 */
  private var applyWorkers: Seq[ApplyWorker] = Seq.empty
  
  /** 偏移量协调器：管理多分区的偏移量状态 */
  private var offsetCoordinator: Option[OffsetCoordinator] = None
  
  /** 偏移量存储：持久化偏移量，支持崩溃恢复 */
  private var offsetStore: Option[OffsetStore] = None
  
  /** 流处理管道：连接所有组件的 Pekko Streams 管道 */
  private var pipeline: Option[CDCStreamPipeline] = None
  
  /** MySQL Sink：幂等地写入目标数据库 */
  private var sink: Option[MySQLSink] = None
  
  // ========== 监控组件 ==========
  
  /** 指标收集器：收集吞吐量、延迟、错误率等指标 */
  private val metrics = CDCMetrics()
  
  /** 健康检查：提供系统健康状态 */
  private var healthCheck: Option[HealthCheck] = None
  
  /** Snapshot 阶段记录的 High Watermark，用于 Streaming 阶段的起始位置 */
  private var snapshotHighWatermark: Option[BinlogPosition] = None
  
  /**
   * 启动 CDC 引擎
   * 
   * 使用 Pekko Streams 模式顺序执行以下阶段：
   * 1. Init: 初始化所有组件（目录服务、过滤器、读取器等）
   * 2. Snapshot: 执行全量快照（当前简化实现，直接跳过）
   * 3. Catchup: 追赶快照期间的变更（当前简化实现，直接跳过）
   * 4. Streaming: 启动实时流处理
   * 
   * 每个阶段包含：
   * - 状态转换：更新 CDC 状态机
   * - 操作执行：执行该阶段的具体逻辑
   * - 日志记录：记录阶段进入和完成
   * 
   * 优势：
   * - 声明式：清晰定义各个阶段
   * - 可监控：每个阶段都有明确的日志
   * - 易扩展：可轻松添加重试、超时等逻辑
   * 
   * @return Future[Done] 启动完成的 Future，失败时会先停止引擎再返回错误
   */
  def start(): Future[Done] = {
    logger.info("Starting CDC Engine")
    
    import org.apache.pekko.stream.scaladsl.Source
    
    /**
     * CDC 生命周期阶段定义
     * @param name 阶段名称，用于日志
     * @param state 目标 CDC 状态
     * @param action 该阶段要执行的操作
     */
    case class Phase(name: String, state: CDCState, action: () => Future[Unit])
    
    // 注意：初始状态已经是 Init，所以第一个阶段直接执行初始化操作
    // 根据配置决定是否包含快照和追赶阶段
    val phases = if (config.offset.enableSnapshot) {
      Seq(
        Phase("Init", Init, () => initializeComponents()),
        Phase("Snapshot", Snapshot, () => performSnapshot()),
        Phase("Catchup", Catchup, () => performCatchup()),
        Phase("Streaming", Streaming, () => startStreaming())
      )
    } else {
      Seq(
        Phase("Init", Init, () => initializeComponents()),
        Phase("Streaming", Streaming, () => startStreaming())
      )
    }
    
    // 使用 Source 流式处理各个阶段
    // mapAsync(1) 确保阶段按顺序执行
    Source(phases)
      .mapAsync(1) { phase =>
        logger.info(s"Entering phase: ${phase.name}")
        // 只有当目标状态与当前状态不同时才进行状态转换
        val transitionFuture = if (getCurrentState() != phase.state) {
          transitionTo(phase.state)
        } else {
          Future.successful(())
        }
        transitionFuture.flatMap(_ => phase.action())
      }
      .runWith(org.apache.pekko.stream.scaladsl.Sink.ignore)
      .map { _ =>
        isRunning = true
        logger.info("CDC Engine started successfully")
        Done
      }
      .recoverWith { case ex =>
        logger.error(s"Failed to start CDC Engine: ${ex.getMessage}", ex)
        stop().flatMap(_ => Future.failed(ex))
      }
  }
  
  /**
   * 停止 CDC 引擎
   * 
   * 执行以下清理操作：
   * 1. 停止流处理管道
   * 2. 关闭数据库连接池
   * 3. 触发关闭信号
   * 
   * @return Future[Done] 停止完成的 Future
   */
  def stop(): Future[Done] = {
    logger.info("Stopping CDC Engine")
    isRunning = false
    
    // 停止流处理管道
    pipeline.foreach(_.stop())
    
    // 关闭连接池
    sink.foreach {
      case pooledSink: PooledMySQLSink => pooledSink.close()
      case _ =>
    }
    
    shutdownPromise.trySuccess(Done)
    logger.info("CDC Engine stopped")
    Future.successful(Done)
  }
  
  /**
   * 获取当前 CDC 状态
   * @return 当前状态（Init/Snapshot/Catchup/Streaming）
   */
  def getCurrentState(): CDCState = currentState.get()
  
  /**
   * 获取指标快照
   * @return 包含吞吐量、延迟、错误率等的指标快照
   */
  def getMetrics(): cn.xuyinyin.cdc.metrics.MetricsSnapshot = metrics.getSnapshot()
  
  /**
   * 获取健康状态
   * @return 健康状态，包含状态、问题列表和检查时间
   */
  def getHealthStatus(): cn.xuyinyin.cdc.health.HealthStatus = {
    healthCheck.map(_.check(getCurrentState(), 1000)).getOrElse(
      cn.xuyinyin.cdc.health.HealthStatus(
        cn.xuyinyin.cdc.health.HealthStatus.Warning,
        Seq.empty,
        java.time.Instant.now()
      )
    )
  }
  
  /**
   * 等待引擎关闭
   * @return Future[Done] 当引擎完全停止时完成
   */
  def awaitTermination(): Future[Done] = shutdownPromise.future
  
  /**
   * 状态转换
   * 
   * 验证状态转换的合法性，只允许以下转换：
   * - Init → Snapshot
   * - Snapshot → Catchup
   * - Catchup → Streaming
   * 
   * @param newState 目标状态
   * @return Future[Unit] 转换完成的 Future
   * @throws IllegalStateException 如果状态转换不合法
   */
  private def transitionTo(newState: CDCState): Future[Unit] = Future {
    val oldState = currentState.get()
    
    if (CDCState.isValidTransition(oldState, newState)) {
      currentState.set(newState)
      logger.info(s"State transition: ${oldState.name} → ${newState.name}")
    } else {
      throw new IllegalStateException(s"Invalid state transition: ${oldState.name} → ${newState.name}")
    }
  }
  
  /**
   * 初始化所有 CDC 组件
   * 
   * 使用 Pekko Streams 模式顺序初始化以下组件：
   * 1. CatalogService: 管理表元数据
   * 2. TableFilter: 过滤需要同步的表
   * 3. OffsetStore: 持久化偏移量
   * 4. OffsetCoordinator: 协调多分区偏移量
   * 5. Sink: MySQL 写入器
   * 6. BinlogReader: Binlog 读取器
   * 7. EventNormalizer: 事件标准化器
   * 8. EventRouter: 事件路由器
   * 9. ApplyWorkers: 并行应用工作器
   * 10. HealthCheck: 健康检查
   * 11. Configuration: 验证配置
   * 
   * 优势：
   * - 每个组件都有明确的名称和日志
   * - 按顺序初始化，确保依赖关系正确
   * - 易于添加新组件或调整顺序
   * 
   * @return Future[Unit] 所有组件初始化完成的 Future
   */
  private def initializeComponents(): Future[Unit] = {
    logger.info("Initializing CDC components")
    
    import org.apache.pekko.stream.scaladsl.Source
    
    /**
     * 组件初始化步骤定义
     * @param name 组件名称，用于日志
     * @param action 初始化操作
     */
    case class InitStep(name: String, action: () => Future[Unit])
    
    val initSteps = Seq(
      InitStep("CatalogService", () => initializeCatalogService()),
      InitStep("TableFilter", () => initializeTableFilter()),
      InitStep("OffsetStore", () => initializeOffsetStore()),
      InitStep("OffsetCoordinator", () => initializeOffsetCoordinator()),
      InitStep("Sink", () => initializeSink()),
      InitStep("BinlogReader", () => initializeBinlogReader()),
      InitStep("EventNormalizer", () => initializeEventNormalizer()),
      InitStep("EventRouter", () => initializeEventRouter()),
      InitStep("ApplyWorkers", () => initializeApplyWorkers()),
      InitStep("HealthCheck", () => initializeHealthCheck()),
      InitStep("Configuration", () => validateConfiguration())
    )
    
    // 使用 Source 流式初始化各个组件
    // mapAsync(1) 确保组件按顺序初始化
    Source(initSteps)
      .mapAsync(1) { step =>
        logger.debug(s"Initializing: ${step.name}")
        step.action()
      }
      .runWith(Sink.ignore)
      .map { _ =>
        logger.info("All CDC components initialized successfully")
      }
  }
  
  /** 初始化目录服务：连接源数据库，管理表元数据 */
  private def initializeCatalogService(): Future[Unit] = Future {
    catalogService = Some(MySQLCatalogService(config.source))
    logger.debug("Catalog service initialized")
  }
  
  /** 初始化表过滤器：根据配置过滤需要同步的表 */
  private def initializeTableFilter(): Future[Unit] = Future {
    tableFilter = Some(TableFilter(config.filter))
    
    // 验证过滤配置
    val validation = tableFilter.get.validateConfig()
    if (!validation.isValid) {
      throw new IllegalArgumentException(s"Invalid filter configuration: ${validation.errors.mkString(", ")}")
    }
    
    validation.warnings.foreach { warning =>
      logger.warn(s"Filter configuration warning: $warning")
    }
    
    logger.debug("Table filter initialized")
  }
  
  /** 初始化偏移量存储：支持 MySQL 和文件两种持久化方式 */
  private def initializeOffsetStore(): Future[Unit] = Future {
    offsetStore = Some(config.offset.storeType match {
      case cn.xuyinyin.cdc.config.MySQLOffsetStore =>
        MySQLOffsetStore(config.target)
      case cn.xuyinyin.cdc.config.FileOffsetStore =>
        val path = config.offset.storeConfig.getOrElse("path", "./data/offsets/offset.txt")
        FileOffsetStore(path)
    })
    logger.debug("Offset store initialized")
  }
  
  /** 初始化偏移量协调器：管理多分区的偏移量状态 */
  private def initializeOffsetCoordinator(): Future[Unit] = Future {
    offsetCoordinator = Some(DefaultOffsetCoordinator(
      config.parallelism.partitionCount,
      offsetStore.get
    ))
    logger.debug("Offset coordinator initialized")
  }
  
  /** 初始化 MySQL Sink：使用连接池的幂等写入器 */
  private def initializeSink(): Future[Unit] = Future {
    sink = Some(PooledMySQLSink(config.target))
    logger.debug("MySQL sink initialized")
  }
  
  /** 初始化 Binlog 读取器：从 MySQL 读取 binlog 事件 */
  private def initializeBinlogReader(): Future[Unit] = Future {
    binlogReader = Some(MySQLBinlogReader(config.source))
    logger.debug("Binlog reader initialized")
  }
  
  /** 初始化事件标准化器：将 binlog 事件转换为标准格式 */
  private def initializeEventNormalizer(): Future[Unit] = Future {
    eventNormalizer = Some(MySQLEventNormalizer(catalogService.get, config.source.database))
    logger.debug("Event normalizer initialized")
  }
  
  /** 初始化事件路由器：基于 hash(table + pk) 的分区路由 */
  private def initializeEventRouter(): Future[Unit] = Future {
    eventRouter = Some(new HashBasedRouter(config.parallelism.partitionCount))
    logger.debug("Event router initialized")
  }
  
  /** 初始化应用工作器：创建多个并行工作器处理事件 */
  private def initializeApplyWorkers(): Future[Unit] = Future {
    applyWorkers = (0 until config.parallelism.partitionCount).map { partition =>
      DefaultApplyWorker(
        partition,
        sink.get,
        offsetCoordinator.get,
        config.parallelism.batchSize
      )
    }
    logger.debug(s"Initialized ${applyWorkers.size} apply workers")
  }
  
  /** 初始化健康检查：提供系统健康状态 */
  private def initializeHealthCheck(): Future[Unit] = Future {
    healthCheck = Some(HealthCheck(metrics))
    logger.debug("Health check initialized")
  }
  
  /**
   * 验证 CDC 配置
   * 
   * 检查以下配置：
   * - Binlog 是否启用
   * - Binlog 格式是否为 ROW（推荐）
   * - Binlog row image 是否为 FULL（推荐）
   * 
   * @return Future[Unit] 验证完成的 Future
   * @throws IllegalStateException 如果 binlog 未启用
   */
  private def validateConfiguration(): Future[Unit] = {
    logger.info("Validating CDC configuration")
    
    catalogService.get.validateBinlogConfig(config.source).map { capability =>
      if (!capability.enabled) {
        throw new IllegalStateException("Binlog is not enabled on source database")
      }
      
      if (capability.format != "ROW") {
        logger.warn(s"Binlog format is ${capability.format}, ROW format is recommended for CDC")
      }
      
      if (capability.rowImage != "FULL") {
        logger.warn(s"Binlog row image is ${capability.rowImage}, FULL image is recommended for CDC")
      }
      
      logger.info(s"Binlog configuration validated: $capability")
    }
  }
  
  /**
   * 执行快照阶段
   * 
   * 为所有需要同步的表创建一致性快照：
   * 1. 发现所有表
   * 2. 过滤需要同步的表
   * 3. 记录 Low Watermark（快照开始时的 binlog 位置）
   * 4. 为每张表执行全量数据复制
   * 5. 记录 High Watermark（快照结束时的 binlog 位置）
   * 
   * @return Future[Unit] 快照完成的 Future
   */
  private def performSnapshot(): Future[Unit] = {
    logger.info("Starting snapshot phase")
    
    // 1. 发现所有表
    catalogService.get.discoverTables(config.filter).flatMap { tables =>
      val filteredTables = tableFilter.get.filterTables(tables.map(_.tableId))
      logger.info(s"Discovered ${filteredTables.size} tables for snapshot")
      
      if (filteredTables.isEmpty) {
        logger.warn("No tables to snapshot, skipping snapshot phase")
        return Future.successful(())
      }
      
      // 2. 记录 Low Watermark
      val lowWatermark = getLatestBinlogPosition()
      logger.info(s"Low Watermark: ${lowWatermark.asString}")
      
      // 3. 为每张表执行快照
      val snapshotFutures = filteredTables.map { tableId =>
        performTableSnapshot(tableId).recover {
          case ex: Exception =>
            logger.error(s"Failed to snapshot table $tableId: ${ex.getMessage}", ex)
            0L // 返回 0 表示失败
        }
      }
      
      // 4. 等待所有快照完成
      Future.sequence(snapshotFutures).map { rowCounts =>
        val totalRows = rowCounts.sum
        val successCount = rowCounts.count(_ > 0)
        logger.info(s"Snapshot completed: $successCount/${filteredTables.size} tables, $totalRows total rows")
        
        // 5. 记录 High Watermark 并保存，用于 Streaming 阶段
        val highWatermark = getLatestBinlogPosition()
        snapshotHighWatermark = Some(highWatermark)
        logger.info(s"High Watermark: ${highWatermark.asString}")
      }
    }
  }
  
  /**
   * 为单个表执行快照
   * 
   * @param tableId 表标识
   * @return Future[Long] 复制的行数
   */
  private def performTableSnapshot(tableId: TableId): Future[Long] = Future {
    import java.sql.DriverManager
    
    logger.info(s"Starting snapshot for table ${tableId.database}.${tableId.table}")
    
    val sourceUrl = s"jdbc:mysql://${config.source.host}:${config.source.port}/${tableId.database}?useSSL=false&allowPublicKeyRetrieval=true"
    val targetUrl = s"jdbc:mysql://${config.target.host}:${config.target.port}/${config.target.database}?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    
    var sourceConn: java.sql.Connection = null
    var targetConn: java.sql.Connection = null
    var rowCount = 0L
    
    try {
      // 连接源数据库
      sourceConn = DriverManager.getConnection(sourceUrl, config.source.username, config.source.password)
      sourceConn.setAutoCommit(false)
      sourceConn.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
      
      // 连接目标数据库
      targetConn = DriverManager.getConnection(targetUrl, config.target.username, config.target.password)
      targetConn.setAutoCommit(false)
      
      // 查询源表数据
      val selectSql = s"SELECT * FROM `${tableId.table}`"
      val selectStmt = sourceConn.createStatement()
      selectStmt.setFetchSize(1000) // 流式读取
      val rs = selectStmt.executeQuery(selectSql)
      
      // 获取列信息
      val metaData = rs.getMetaData
      val columnCount = metaData.getColumnCount
      val columnNames = (1 to columnCount).map(metaData.getColumnName).mkString(", ")
      val placeholders = (1 to columnCount).map(_ => "?").mkString(", ")
      
      // 准备插入语句（使用 REPLACE INTO 实现幂等）
      val insertSql = s"REPLACE INTO `${tableId.table}` ($columnNames) VALUES ($placeholders)"
      val insertStmt = targetConn.prepareStatement(insertSql)
      
      // 批量插入
      val batchSize = config.parallelism.batchSize
      var batchCount = 0
      
      while (rs.next()) {
        // 设置参数
        for (i <- 1 to columnCount) {
          insertStmt.setObject(i, rs.getObject(i))
        }
        insertStmt.addBatch()
        batchCount += 1
        rowCount += 1
        
        // 执行批量插入
        if (batchCount >= batchSize) {
          insertStmt.executeBatch()
          targetConn.commit()
          batchCount = 0
          
          if (rowCount % 10000 == 0) {
            logger.info(s"Snapshot progress for ${tableId.table}: $rowCount rows")
          }
        }
      }
      
      // 执行剩余的批量
      if (batchCount > 0) {
        insertStmt.executeBatch()
        targetConn.commit()
      }
      
      logger.info(s"Snapshot completed for table ${tableId.database}.${tableId.table}: $rowCount rows")
      rowCount
      
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to snapshot table ${tableId.database}.${tableId.table}: ${ex.getMessage}", ex)
        if (targetConn != null) {
          try {
            targetConn.rollback()
          } catch {
            case _: Exception => // 忽略回滚错误
          }
        }
        throw ex
    } finally {
      if (sourceConn != null) try { sourceConn.close() } catch { case _: Exception => }
      if (targetConn != null) try { targetConn.close() } catch { case _: Exception => }
    }
  }
  
  /**
   * 执行 Catchup 阶段
   * 
   * 当前为简化实现，直接跳过。
   * 
   * 完整实现应该：
   * 1. 获取每张表的 Low Watermark
   * 2. 从 Low Watermark 开始读取 binlog
   * 3. 过滤目标表的事件
   * 4. 应用到目标数据库
   * 5. 追赶到 High Watermark
   * 
   * 参考 CDCEngineWithSnapshot 和 CatchupProcessor 获取完整实现。
   * 
   * @return Future[Unit] Catchup 完成的 Future
   */
  private def performCatchup(): Future[Unit] = {
    logger.info("Performing catchup phase (simplified - skipping)")
    Future.successful(())
  }
  
  /**
   * 启动流处理阶段
   * 
   * 执行以下操作：
   * 1. 获取起始位置（从上次提交的偏移量恢复，或从初始位置开始）
   * 2. 创建流处理管道（连接所有组件）
   * 3. 启动管道（异步运行）
   * 4. 监控管道状态（成功或失败时停止引擎）
   * 
   * 流处理管道：
   * BinlogReader → EventNormalizer → EventRouter → ApplyWorkers → MySQLSink
   * 
   * @return Future[Unit] 流处理启动完成的 Future（不等待流处理结束）
   */
  private def startStreaming(): Future[Unit] = {
    logger.info("Starting streaming phase")
    
    // 获取起始位置：
    // 1. 如果刚完成 Snapshot，优先使用 High Watermark（快照结束时的位置）
    // 2. 否则使用上次提交的偏移量（支持崩溃恢复）
    // 3. 都没有则根据配置决定（从最新或从头开始）
    val startPosition = snapshotHighWatermark
      .orElse(offsetCoordinator.get.getLastCommittedPosition())
      .getOrElse {
        if (config.offset.startFromLatest) {
          // 从最新位置开始（跳过历史数据）
          logger.info("No previous offset found, starting from latest binlog position")
          getLatestBinlogPosition()
        } else {
          // 从头开始（处理所有历史数据）
          logger.info("No previous offset found, starting from beginning")
          FilePosition("mysql-bin.000001", 4L)
        }
      }
    
    logger.info(s"Starting CDC stream from position: ${startPosition.asString}")
    
    // 创建流处理管道
    pipeline = Some(CDCStreamPipeline(
      config,
      binlogReader.get,
      eventNormalizer.get,
      eventRouter.get,
      applyWorkers,
      offsetCoordinator.get
    ))
    
    // 启动管道（异步运行）
    val pipelineFuture = pipeline.get.run(startPosition)
    
    // 监控管道运行状态
    pipelineFuture.onComplete {
      case Success(_) =>
        logger.info("CDC pipeline completed successfully")
        stop()
      case Failure(ex) =>
        logger.error(s"CDC pipeline failed: ${ex.getMessage}", ex)
        metrics.recordError()
        stop()
    }
    
    Future.successful(())
  }
  
  /**
   * 获取最新的 binlog 位置
   * 通过查询 MySQL 的 SHOW BINARY LOG STATUS 或 SHOW MASTER STATUS 获取当前最新的 binlog 文件和位置
   * 支持 MySQL 8.2+ 的新语法和旧版本的兼容性
   */
  private def getLatestBinlogPosition(): FilePosition = {
    import java.sql.{DriverManager, SQLException}
    
    val url = s"jdbc:mysql://${config.source.host}:${config.source.port}/${config.source.database}?useSSL=false&allowPublicKeyRetrieval=true"
    
    var conn: java.sql.Connection = null
    try {
      conn = DriverManager.getConnection(url, config.source.username, config.source.password)
      
      // 尝试新语法（MySQL 8.2+）
      val position = try {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SHOW BINARY LOG STATUS")
        
        if (rs.next()) {
          val filename = rs.getString("File")
          val pos = rs.getLong("Position")
          logger.info(s"Latest binlog position (BINARY LOG STATUS): $filename:$pos")
          Some(FilePosition(filename, pos))
        } else {
          None
        }
      } catch {
        case _: SQLException =>
          // 新语法失败，尝试旧语法
          logger.debug("SHOW BINARY LOG STATUS not supported, trying SHOW MASTER STATUS")
          None
      }
      
      // 如果新语法失败，尝试旧语法（MySQL 5.x - 8.1）
      position.getOrElse {
        try {
          val stmt = conn.createStatement()
          val rs = stmt.executeQuery("SHOW MASTER STATUS")
          
          if (rs.next()) {
            val filename = rs.getString("File")
            val pos = rs.getLong("Position")
            logger.info(s"Latest binlog position (MASTER STATUS): $filename:$pos")
            FilePosition(filename, pos)
          } else {
            logger.warn("No binlog status found, falling back to default position")
            FilePosition("mysql-bin.000001", 4L)
          }
        } catch {
          case ex: SQLException =>
            logger.error(s"Failed to get binlog position: ${ex.getMessage}. Check if binlog is enabled and user has REPLICATION CLIENT privilege.")
            logger.warn("Falling back to default position: mysql-bin.000001:4")
            FilePosition("mysql-bin.000001", 4L)
        }
      }
      
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to connect to MySQL: ${ex.getMessage}", ex)
        logger.warn("Falling back to default position: mysql-bin.000001:4")
        FilePosition("mysql-bin.000001", 4L)
    } finally {
      if (conn != null) {
        try {
          conn.close()
        } catch {
          case ex: Exception =>
            logger.warn(s"Failed to close connection: ${ex.getMessage}")
        }
      }
    }
  }
  
  /**
   * 获取组件状态信息
   * 
   * 返回所有组件的初始化状态和当前指标，用于监控和调试。
   * 
   * @return Map[String, Any] 包含状态、组件状态和指标的 Map
   */
  def getComponentStatus(): Map[String, Any] = {
    Map(
      "state" -> getCurrentState().name,
      "isRunning" -> isRunning,
      "catalogService" -> catalogService.isDefined,
      "binlogReader" -> binlogReader.isDefined,
      "eventNormalizer" -> eventNormalizer.isDefined,
      "offsetCoordinator" -> offsetCoordinator.isDefined,
      "pipeline" -> pipeline.isDefined,
      "metrics" -> metrics.getSnapshot().toMap
    )
  }
}

object CDCEngine {
  /**
   * 创建 CDC Engine 实例
   * 
   * 工厂方法，提供更简洁的创建方式。
   * 
   * @param config CDC 配置
   * @param mat Pekko Streams Materializer
   * @param ec 执行上下文
   * @return CDCEngine 实例
   */
  def apply(config: CDCConfig)(implicit mat: Materializer, ec: ExecutionContext): CDCEngine = {
    new CDCEngine(config)
  }
}