# 基于 Pekko Streams 的 CDC 生命周期管理

## 概述

CDC 引擎的启动过程涉及多个顺序执行的阶段（Init → Snapshot → Catchup → Streaming）。我们使用 Pekko Streams 来优雅地管理这个生命周期，而不是传统的 for-comprehension 方式。

## 传统方式 vs Streams 方式

### 传统方式（for-comprehension）

```scala
def start(): Future[Done] = {
  for {
    _ <- transitionTo(Init)
    _ <- initializeComponents()
    _ <- transitionTo(Snapshot)
    _ <- performSnapshot()
    _ <- transitionTo(Catchup)
    _ <- performCatchup()
    _ <- transitionTo(Streaming)
    _ <- startStreaming()
  } yield {
    isRunning = true
    Done
  }
}.recoverWith { case ex =>
  stop().flatMap(_ => Future.failed(ex))
}
```

**缺点**：
- 代码冗长，重复的状态转换调用
- 不够声明式
- 难以添加监控和日志
- 不符合 Pekko Streams 的设计理念

### Streams 方式（推荐）

```scala
def start(): Future[Done] = {
  import org.apache.pekko.stream.scaladsl.Source
  
  // 定义 CDC 生命周期阶段
  case class Phase(name: String, state: CDCState, action: () => Future[Unit])
  
  val phases = Seq(
    Phase("Init", Init, () => initializeComponents()),
    Phase("Snapshot", Snapshot, () => performSnapshot()),
    Phase("Catchup", Catchup, () => performCatchup()),
    Phase("Streaming", Streaming, () => startStreaming())
  )
  
  // 使用 Source 流式处理各个阶段
  Source(phases)
    .mapAsync(1) { phase =>
      logger.info(s"Entering phase: ${phase.name}")
      transitionTo(phase.state).flatMap(_ => phase.action())
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
```

**优点**：
- ✅ 声明式：清晰地定义了各个阶段
- ✅ 可组合：易于添加、删除或重排阶段
- ✅ 可监控：每个阶段都有明确的日志
- ✅ 符合 Pekko 设计理念：使用 Streams 处理顺序操作
- ✅ 易于扩展：可以轻松添加重试、超时等逻辑

## 核心概念

### Phase（阶段）

```scala
case class Phase(
  name: String,              // 阶段名称（用于日志）
  state: CDCState,           // 目标状态
  action: () => Future[Unit] // 要执行的操作
)
```

每个阶段包含：
1. **名称**：用于日志和监控
2. **状态**：CDC 状态机的目标状态
3. **操作**：该阶段要执行的异步操作

### InitStep（初始化步骤）

同样的模式也应用于组件初始化：

```scala
case class InitStep(
  name: String,              // 组件名称
  action: () => Future[Unit] // 初始化操作
)

val initSteps = Seq(
  InitStep("CatalogService", () => initializeCatalogService()),
  InitStep("TableFilter", () => initializeTableFilter()),
  InitStep("OffsetStore", () => initializeOffsetStore()),
  // ...
)

Source(initSteps)
  .mapAsync(1) { step =>
    logger.debug(s"Initializing: ${step.name}")
    step.action()
  }
  .runWith(Sink.ignore)
```

**优势**：
- 清晰的组件初始化顺序
- 每个组件都有明确的日志
- 易于添加或删除组件
- 可以轻松添加初始化超时、重试等逻辑

### 流式处理

```scala
Source(phases)              // 创建阶段源
  .mapAsync(1) { phase =>   // 顺序处理（并发度=1）
    transitionTo(phase.state)
      .flatMap(_ => phase.action())
  }
  .runWith(Sink.ignore)     // 运行流
```

**关键点**：
- `mapAsync(1)`：确保阶段按顺序执行（并发度为 1）
- `transitionTo` + `action`：先转换状态，再执行操作
- `Sink.ignore`：我们不关心中间结果，只关心最终完成

## 完整示例

### CDCEngine 中的应用

#### 1. 组件初始化

```scala
private def initializeComponents(): Future[Unit] = {
  logger.info("Initializing CDC components")
  
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
```

#### 2. 生命周期管理

```scala
def start(): Future[Done] = {
  logger.info("Starting CDC Engine")
  
  case class Phase(name: String, state: CDCState, action: () => Future[Unit])
  
  val phases = Seq(
    Phase("Init", Init, () => initializeComponents()),
    Phase("Snapshot", Snapshot, () => performSnapshot()),
    Phase("Catchup", Catchup, () => performCatchup()),
    Phase("Streaming", Streaming, () => startStreaming())
  )
  
  Source(phases)
    .mapAsync(1) { phase =>
      logger.info(s"Entering phase: ${phase.name}")
      transitionTo(phase.state).flatMap(_ => phase.action())
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
```

### 对比：传统方式 vs Streams 方式

#### 组件初始化

**传统方式**：
```scala
private def initializeComponents(): Future[Unit] = {
  for {
    _ <- initializeCatalogService()
    _ <- initializeTableFilter()
    _ <- initializeOffsetStore()
    _ <- initializeOffsetCoordinator()
    _ <- initializeSink()
    _ <- initializeBinlogReader()
    _ <- initializeEventNormalizer()
    _ <- initializeEventRouter()
    _ <- initializeApplyWorkers()
    _ <- initializeHealthCheck()
    _ <- validateConfiguration()
  } yield {
    logger.info("All CDC components initialized successfully")
  }
}
```

**Streams 方式**：
```scala
private def initializeComponents(): Future[Unit] = {
  val initSteps = Seq(
    InitStep("CatalogService", () => initializeCatalogService()),
    InitStep("TableFilter", () => initializeTableFilter()),
    // ... 其他组件
  )
  
  Source(initSteps)
    .mapAsync(1)(step => step.action())
    .runWith(Sink.ignore)
}
```

**改进**：
- ✅ 每个组件都有明确的名称
- ✅ 易于添加日志和监控
- ✅ 可以轻松跳过某些组件
- ✅ 代码更加声明式

## 扩展示例

### 1. 添加超时控制

```scala
Source(phases)
  .mapAsync(1) { phase =>
    logger.info(s"Entering phase: ${phase.name}")
    
    val timeout = phase.name match {
      case "Snapshot" => 2.hours
      case "Catchup" => 1.hour
      case _ => 10.minutes
    }
    
    Future.firstCompletedOf(Seq(
      transitionTo(phase.state).flatMap(_ => phase.action()),
      Future {
        Thread.sleep(timeout.toMillis)
        throw new TimeoutException(s"Phase ${phase.name} timeout")
      }
    ))
  }
  .runWith(Sink.ignore)
```

### 2. 添加重试逻辑

```scala
Source(phases)
  .mapAsync(1) { phase =>
    logger.info(s"Entering phase: ${phase.name}")
    
    def attemptPhase(retries: Int): Future[Unit] = {
      transitionTo(phase.state)
        .flatMap(_ => phase.action())
        .recoverWith {
          case ex if retries > 0 =>
            logger.warn(s"Phase ${phase.name} failed, retrying... ($retries left)")
            Thread.sleep(5000)
            attemptPhase(retries - 1)
          case ex =>
            Future.failed(ex)
        }
    }
    
    attemptPhase(3) // 最多重试 3 次
  }
  .runWith(Sink.ignore)
```

### 3. 添加进度监控

```scala
Source(phases)
  .zipWithIndex
  .mapAsync(1) { case (phase, index) =>
    val progress = ((index + 1).toDouble / phases.size * 100).toInt
    logger.info(s"Progress: $progress% - Entering phase: ${phase.name}")
    
    metrics.recordGauge("cdc_startup_progress", progress)
    
    transitionTo(phase.state).flatMap(_ => phase.action())
  }
  .runWith(Sink.ignore)
```

### 4. 添加阶段跳过逻辑

```scala
val phases = Seq(
  Phase("Init", Init, () => initializeComponents(), required = true),
  Phase("Snapshot", Snapshot, () => performSnapshot(), required = needSnapshot),
  Phase("Catchup", Catchup, () => performCatchup(), required = needCatchup),
  Phase("Streaming", Streaming, () => startStreaming(), required = true)
)

Source(phases)
  .filter(_.required) // 只处理必需的阶段
  .mapAsync(1) { phase =>
    logger.info(s"Entering phase: ${phase.name}")
    transitionTo(phase.state).flatMap(_ => phase.action())
  }
  .runWith(Sink.ignore)
```

### 5. 添加阶段间的依赖检查

```scala
Source(phases)
  .mapAsync(1) { phase =>
    logger.info(s"Entering phase: ${phase.name}")
    
    // 检查前置条件
    phase.name match {
      case "Snapshot" if !catalogService.isDefined =>
        Future.failed(new IllegalStateException("Catalog service not initialized"))
      case "Streaming" if !pipeline.isDefined =>
        Future.failed(new IllegalStateException("Pipeline not initialized"))
      case _ =>
        transitionTo(phase.state).flatMap(_ => phase.action())
    }
  }
  .runWith(Sink.ignore)
```

## 与其他 Pekko Streams 组件集成

### 与背压控制集成

```scala
Source(phases)
  .mapAsync(1) { phase =>
    // 检查系统负载
    if (metrics.getQueueDepth() > 1000) {
      logger.warn("High queue depth, slowing down startup")
      Thread.sleep(1000)
    }
    
    transitionTo(phase.state).flatMap(_ => phase.action())
  }
  .runWith(Sink.ignore)
```

### 与监控集成

```scala
Source(phases)
  .alsoTo(Flow[Phase].map { phase =>
    // 发送监控事件
    monitoringService.recordEvent(s"cdc_phase_${phase.name}_started")
  }.to(Sink.ignore))
  .mapAsync(1) { phase =>
    val startTime = System.currentTimeMillis()
    
    transitionTo(phase.state)
      .flatMap(_ => phase.action())
      .andThen { case _ =>
        val duration = System.currentTimeMillis() - startTime
        metrics.recordHistogram(s"cdc_phase_${phase.name}_duration", duration)
      }
  }
  .runWith(Sink.ignore)
```

## 优势总结

### 1. 声明式编程

阶段定义清晰，易于理解：
```scala
val phases = Seq(
  Phase("Init", Init, () => initializeComponents()),
  Phase("Snapshot", Snapshot, () => performSnapshot()),
  // ...
)
```

### 2. 易于测试

可以轻松测试单个阶段：
```scala
test("Init phase should initialize all components") {
  val phase = Phase("Init", Init, () => initializeComponents())
  phase.action().map { _ =>
    assert(catalogService.isDefined)
    assert(binlogReader.isDefined)
  }
}
```

### 3. 灵活的错误处理

可以为不同阶段定制错误处理策略：
```scala
Source(phases)
  .mapAsync(1) { phase =>
    phase.action().recoverWith {
      case ex: SnapshotException if phase.name == "Snapshot" =>
        logger.warn("Snapshot failed, skipping to Streaming")
        Future.successful(())
      case ex =>
        Future.failed(ex)
    }
  }
  .runWith(Sink.ignore)
```

### 4. 符合 Reactive Streams 规范

- 支持背压
- 支持取消
- 支持异步处理
- 与其他 Pekko Streams 组件无缝集成

## 性能考虑

### 并发度

```scala
.mapAsync(1) { ... }  // 顺序执行（推荐）
.mapAsync(4) { ... }  // 并发执行（不推荐，会破坏顺序）
```

**建议**：保持并发度为 1，确保阶段按顺序执行。

### 内存使用

```scala
Source(phases)  // 内存中只保存阶段定义，不会预先执行
  .mapAsync(1) { phase =>
    phase.action()  // 按需执行
  }
```

**优势**：惰性执行，不会一次性加载所有阶段的数据。

## 最佳实践

1. **保持阶段独立**：每个阶段应该是自包含的，不依赖其他阶段的内部状态
2. **使用有意义的名称**：阶段名称应该清晰地描述其功能
3. **添加适当的日志**：在每个阶段的开始和结束记录日志
4. **处理错误**：为每个阶段提供适当的错误处理
5. **监控进度**：记录每个阶段的执行时间和状态

## 总结

使用 Pekko Streams 管理 CDC 生命周期的优势：

- ✅ 更加声明式和函数式
- ✅ 易于扩展和维护
- ✅ 符合 Pekko 的设计理念
- ✅ 提供更好的监控和日志
- ✅ 支持灵活的错误处理和重试
- ✅ 与其他 Pekko Streams 组件无缝集成

这种方式不仅让代码更加优雅，也为未来的扩展提供了更好的基础。
