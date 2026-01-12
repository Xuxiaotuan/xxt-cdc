package cn.xuyinyin.cdc.engine

import cn.xuyinyin.cdc.catalog.CatalogService
import cn.xuyinyin.cdc.config.CDCConfig
import cn.xuyinyin.cdc.connector.{ConnectorBootstrap, ConnectorRegistry, DataWriter, SinkConnector, SourceConnector}
import cn.xuyinyin.cdc.coordinator.{DefaultOffsetCoordinator, FileOffsetStore, MySQLOffsetStore, OffsetCoordinator, OffsetStore}
import cn.xuyinyin.cdc.filter.TableFilter
import cn.xuyinyin.cdc.health.{HealthCheck, HealthStatus}
import cn.xuyinyin.cdc.logging.CDCLogging
import cn.xuyinyin.cdc.metrics.{CDCMetrics, MetricsSnapshot}
import cn.xuyinyin.cdc.model._
import cn.xuyinyin.cdc.normalizer.EventNormalizer
import cn.xuyinyin.cdc.pipeline.CDCStreamPipeline
import cn.xuyinyin.cdc.reader.BinlogReader
import cn.xuyinyin.cdc.router.{EventRouter, HashBasedRouter}
import cn.xuyinyin.cdc.worker.{ApplyWorker, DefaultApplyWorker}
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
class CDCEngine(config: CDCConfig)(implicit mat: Materializer, ec: ExecutionContext) extends CDCLogging {
  
  // ========== 状态管理 ==========
  
  /** 当前 CDC 状态，使用 AtomicReference 保证线程安全 */
  private val currentState = new AtomicReference[CDCState](Init)
  
  /** 引擎是否正在运行 */
  @volatile private var isRunning = false
  
  /** 关闭信号，用于等待引擎完全停止 */
  private val shutdownPromise = Promise[Done]()
  
  // ========== 核心组件 ==========
  
  /** Source Connector：源数据库连接器 */
  private var sourceConnector: Option[SourceConnector] = None
  
  /** Sink Connector：目标数据库连接器 */
  private var sinkConnector: Option[SinkConnector] = None
  
  /** 目录服务：管理表元数据、验证 binlog 配置 */
  private var catalogService: Option[CatalogService] = None
  
  /** 表过滤器：根据配置过滤需要同步的表 */
  private var tableFilter: Option[TableFilter] = None
  
  /** Binlog 读取器：从源数据库读取变更日志事件 */
  private var binlogReader: Option[BinlogReader] = None
  
  /** 事件标准化器：将原始事件转换为标准格式 */
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
  
  /** 数据写入器：幂等地写入目标数据库 */
  private var writer: Option[DataWriter] = None
  
  // ========== 监控组件 ==========
  
  /** 指标收集器：收集吞吐量、延迟、错误率等指标 */
  private val metrics = CDCMetrics()
  
  /** 健康检查：提供系统健康状态 */
  private var healthCheck: Option[HealthCheck] = None
  
  /** 性能指标日志输出器：定期输出性能指标 */
  private var performanceLogger: Option[cn.xuyinyin.cdc.logging.PerformanceLogger] = None
  
  /** Snapshot 阶段记录的 Low Watermark，用于 Catchup 阶段的起始位置 */
  private var snapshotLowWatermark: Option[BinlogPosition] = None
  
  /** Snapshot 阶段记录的 High Watermark，用于 Catchup 阶段的结束位置和 Streaming 阶段的起始位置 */
  private var snapshotHighWatermark: Option[BinlogPosition] = None
  
  /** Snapshot 阶段需要同步的表列表，用于 Catchup 阶段过滤事件 */
  private var snapshotTables: Set[TableId] = Set.empty
  
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
      .runWith(Sink.ignore)
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
    
    // 停止性能日志输出器
    performanceLogger.foreach(_.stop())
    
    // 停止流处理管道
    pipeline.foreach(_.stop())
    
    // 关闭数据写入器
    writer.foreach(_.close())
    
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
  def getMetrics(): MetricsSnapshot = metrics.getSnapshot()
  
  /**
   * 获取健康状态
   * @return 健康状态，包含状态、问题列表和检查时间
   */
  def getHealthStatus(): HealthStatus = {
    healthCheck.map(_.check(getCurrentState(), 1000)).getOrElse(
      HealthStatus(
        HealthStatus.Warning,
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
      logBold(s"State transition: ${oldState.name} → ${newState.name}")
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
      InitStep("ConnectorBootstrap", () => initializeConnectors()),
      InitStep("CatalogService", () => initializeCatalogService()),
      InitStep("TableFilter", () => initializeTableFilter()),
      InitStep("OffsetStore", () => initializeOffsetStore()),
      InitStep("OffsetCoordinator", () => initializeOffsetCoordinator()),
      InitStep("DataWriter", () => initializeWriter()),
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
  
  /** 初始化 Connector 框架 */
  private def initializeConnectors(): Future[Unit] = Future {
    // 初始化 Connector 注册中心
    ConnectorBootstrap.initialize()
    
    // 获取 Source Connector
    sourceConnector = Some(ConnectorRegistry.getSource(config.sourceType))
    logger.info(s"Using source connector: ${config.sourceType} (version ${sourceConnector.get.version})")
    
    // 获取 Sink Connector
    sinkConnector = Some(ConnectorRegistry.getSink(config.targetType))
    logger.info(s"Using sink connector: ${config.targetType} (version ${sinkConnector.get.version})")
    
    // 验证 Connector 配置
    sourceConnector.get.validateConfig(config.source).foreach { error =>
      throw new IllegalArgumentException(s"Invalid source config: $error")
    }
    sinkConnector.get.validateConfig(config.target).foreach { error =>
      throw new IllegalArgumentException(s"Invalid target config: $error")
    }
    
    logger.debug("Connectors initialized")
  }
  
  /** 初始化目录服务：使用 Source Connector 创建 */
  private def initializeCatalogService(): Future[Unit] = Future {
    catalogService = Some(sourceConnector.get.createCatalog(config.source))
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
        MySQLOffsetStore(config.metadata, config.taskName)
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
  
  /** 初始化数据写入器：使用 Sink Connector 创建 */
  private def initializeWriter(): Future[Unit] = Future {
    writer = Some(sinkConnector.get.createWriter(config.target))
    logger.debug("Data writer initialized")
  }
  
  /** 初始化 Binlog 读取器：使用 Source Connector 创建 */
  private def initializeBinlogReader(): Future[Unit] = Future {
    binlogReader = Some(sourceConnector.get.createReader(config.source))
    logger.debug("Binlog reader initialized")
  }
  
  /** 初始化事件标准化器：使用 Source Connector 创建 */
  private def initializeEventNormalizer(): Future[Unit] = Future {
    eventNormalizer = Some(sourceConnector.get.createNormalizer(catalogService.get, config.source.database))
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
        writer.get,
        offsetCoordinator.get,
        config.parallelism.batchSize,
        Some(metrics)  // 传递 metrics
      )
    }
    logger.debug(s"Initialized ${applyWorkers.size} apply workers")
  }
  
  /** 初始化健康检查：提供系统健康状态 */
  private def initializeHealthCheck(): Future[Unit] = Future {
    healthCheck = Some(HealthCheck(metrics))
    
    // 同时初始化性能指标日志输出器
    performanceLogger = Some(cn.xuyinyin.cdc.logging.PerformanceLogger(metrics))
    performanceLogger.foreach(_.start())
    
    logger.debug("Health check and performance logger initialized")
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
      snapshotLowWatermark = Some(lowWatermark)
      logger.info(s"Low Watermark: ${lowWatermark.asString}")
      
      // 保存快照表列表，用于 Catchup 阶段过滤
      snapshotTables = filteredTables.toSet
      logger.info(s"Snapshot tables: ${snapshotTables.map(_.toString).mkString(", ")}")
      
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
   */
  private def performTableSnapshot(tableId: TableId): Future[Long] = {
    CDCEngineUtils.performTableSnapshot(tableId, config)
  }
  
  /**
   * 执行 Catchup 阶段
   * 
   * 从 Low Watermark 追赶到 High Watermark，处理快照期间产生的增量变更：
   * 1. 验证 Low 和 High Watermark 是否存在
   * 2. 检查是否需要 catchup（Low < High）
   * 3. 从 Low Watermark 开始读取 binlog
   * 4. 过滤快照表的事件
   * 5. 应用到目标数据库
   * 6. 追赶到 High Watermark
   * 
   * @return Future[Unit] Catchup 完成的 Future
   */
  private def performCatchup(): Future[Unit] = {
    logger.info("Starting catchup phase")
    
    (snapshotLowWatermark, snapshotHighWatermark) match {
      case (Some(lowWm), Some(highWm)) =>
        // 比较 Low 和 High Watermark
        if (lowWm.compare(highWm) >= 0) {
          logger.info("Low watermark >= High watermark, no catchup needed")
          Future.successful(())
        } else {
          logger.info(s"Catchup range: ${lowWm.asString} → ${highWm.asString}")
          logger.info(s"Catchup will process ${snapshotTables.size} tables: ${snapshotTables.map(_.toString).mkString(", ")}")
          performCatchupRange(lowWm, highWm)
        }
      case (None, _) =>
        logger.warn("No low watermark found, skipping catchup phase")
        Future.successful(())
      case (_, None) =>
        logger.warn("No high watermark found, skipping catchup phase")
        Future.successful(())
    }
  }
  
  /**
   * 执行指定范围的 Catchup 处理
   */
  private def performCatchupRange(lowWatermark: BinlogPosition, highWatermark: BinlogPosition): Future[Unit] = {
    CDCEngineUtils.performCatchupRange(
      lowWatermark,
      highWatermark,
      snapshotTables,
      config,
      eventNormalizer.get,
      eventRouter.get,
      applyWorkers
    ).andThen {
      case Success(_) =>
        // 清理 watermark 状态，为下次 snapshot 做准备
        snapshotLowWatermark = None
        snapshotTables = Set.empty
      case Failure(_) =>
        // 即使失败也清理状态
        snapshotLowWatermark = None
        snapshotTables = Set.empty
    }
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
      offsetCoordinator.get,
      Some(metrics)  // 传递 metrics
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
   */
  private def getLatestBinlogPosition(): FilePosition = {
    CDCEngineUtils.getLatestBinlogPosition(config)
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