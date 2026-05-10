package cn.xuyinyin.cdc

import cn.xuyinyin.cdc.api.CDCManagementAPI
import cn.xuyinyin.cdc.cluster.{ClusterStateProbe, MySQLSingletonLock, TaskSingletonBehavior, TaskSingletonManager}
import cn.xuyinyin.cdc.config.{ConfigLoader, ConfigValidator}
import cn.xuyinyin.cdc.engine.CDCEngine
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * CDC 应用程序主入口
 *
 * Phase 1（Cluster 引导）新增 CLI 参数：
 *   --role master|worker         节点角色（默认 worker）
 *   --port <num>                 Pekko remote 端口（默认 2551）
 *   --seed-nodes host:port,...   种子节点列表（默认: self-seed 单节点 cluster）
 *   --config <file>              CDC 业务配置文件（保留旧逻辑）
 */
object CDCApplication extends App with LazyLogging {

  // ========== CLI 参数解析 ==========

  private case class CliArgs(
    role: String = "worker",
    port: Int = 2551,
    seedNodes: Seq[String] = Seq.empty,
    configFile: Option[String] = None
  )

  private def parseArgs(args: Array[String]): CliArgs = {
    val it = args.iterator
    var acc = CliArgs()
    while (it.hasNext) {
      it.next() match {
        case "--role" if it.hasNext       => acc = acc.copy(role = it.next())
        case "--port" if it.hasNext       => acc = acc.copy(port = it.next().toInt)
        case "--seed-nodes" if it.hasNext =>
          acc = acc.copy(seedNodes = it.next().split(",").toSeq.map(_.trim).filter(_.nonEmpty))
        case "--config" if it.hasNext     => acc = acc.copy(configFile = Some(it.next()))
        case other =>
          // 兼容旧用法：单参数当作 --config 文件路径
          if (acc.configFile.isEmpty && !other.startsWith("--")) acc = acc.copy(configFile = Some(other))
      }
    }
    acc
  }

  private val cliArgs = parseArgs(args)
  logger.info(
    s"CLI: role=${cliArgs.role}, port=${cliArgs.port}, " +
      s"seed-nodes=${if (cliArgs.seedNodes.isEmpty) "[self]" else cliArgs.seedNodes.mkString(",")}"
  )

  // ========== 构建 Pekko Config（CLI 覆盖 reference.conf）==========

  /** ActorSystem name —— 同一 cluster 内所有节点必须一致 */
  private val SystemName = "cdc-system"

  private val pekkoConfig: Config = {
    // 默认种子节点：自己（单节点 cluster 也能起来）
    val seeds: Seq[String] =
      if (cliArgs.seedNodes.nonEmpty) cliArgs.seedNodes
      else Seq(s"127.0.0.1:${cliArgs.port}")

    val seedNodesHocon = seeds
      .map(addr => s""""pekko://$SystemName@$addr"""")
      .mkString("[", ",", "]")

    val overrides = ConfigFactory.parseString(
      s"""
         |pekko.cluster.roles = ["${cliArgs.role}"]
         |pekko.cluster.seed-nodes = $seedNodesHocon
         |pekko.remote.artery.canonical.port = ${cliArgs.port}
         |""".stripMargin
    )

    val cdcBase = cliArgs.configFile match {
      case Some(file) =>
        logger.info(s"Loading Pekko config overlay from: $file")
        ConfigFactory.parseFile(new java.io.File(file)).withFallback(ConfigFactory.load())
      case None =>
        ConfigFactory.load()
    }

    overrides.withFallback(cdcBase).resolve()
  }

  logger.info("Starting MySQL CDC Engine")

  // ========== 创建 Cluster ActorSystem ==========

  implicit val system: ActorSystem                = ActorSystem(SystemName, pekkoConfig)
  implicit val materializer: Materializer         = Materializer(system)
  implicit val executionContext: ExecutionContext = system.dispatcher

  // ========== 启动 Cluster State Probe ==========

  private val clusterProbe = system.actorOf(
    ClusterStateProbe.props(),
    "cluster-state-probe"
  )
  logger.info(s"Cluster state probe started at ${clusterProbe.path}")

  // 注册关闭钩子
  sys.addShutdownHook {
    logger.info("Shutting down CDC Engine")
    system.terminate()
  }

  // ========== 启动 CDC 应用 ==========

  // Phase 2.1: CDCEngine 不再由 main 主流程直接创建与启停，
  // 而是交给 ClusterSingleton 托管：
  //   - master 节点：ClusterSingleton 在本节点 spawn TaskSingletonBehavior，
  //                Behavior 内部调用 engineFactory() 创建 engine 并 start
  //   - worker 节点：ClusterSingleton.init 返回一个透明代理 ActorRef，不 spawn engine
  //
  // Phase 2.2: SingletonLock 改用 MySQLSingletonLock，把 task 行锁写到 metadata DB，
  // 提供跨节点 belt-and-suspenders 防护（防 SBR 失效或网络极端分裂时的双 master 双跑）。

  private val applicationFuture = for {
    config <- Future {
      cliArgs.configFile match {
        case Some(file) =>
          logger.info(s"Loading CDC config from file: $file")
          ConfigLoader.loadFromFile(file)
        case None =>
          logger.info("Loading CDC config from application.conf / reference.conf")
          ConfigLoader.load()
      }
    }

    _ <- Future {
      logger.info("Validating configuration")
      val validation = ConfigValidator.validate(config)
      validation.logResults()

      if (!validation.isValid) {
        throw new IllegalArgumentException("Configuration validation failed")
      }
    }

    // 用 metadata DatabaseConfig 创建跨节点行锁
    singletonLock <- Future {
      logger.info(s"Initializing MySQL singleton lock on metadata DB: ${config.metadata.host}:${config.metadata.port}/${config.metadata.database}")
      MySQLSingletonLock.fromConfig(config.metadata)
    }

    // engineFactory 仅会在 master 节点上被 Singleton spawn 时调用
    taskRef <- Future {
      val engineFactory: () => CDCEngine = () => CDCEngine(config)
      val ref = TaskSingletonManager.init(
        system      = system.toTyped,
        taskId      = config.taskName,
        behavior    = TaskSingletonBehavior(
          taskId        = config.taskName,
          engineFactory = engineFactory,
          lockOpt       = Some(singletonLock)
        ),
        hostingRole = "master"
      )
      logger.info(s"Task Singleton initialized: taskId=${config.taskName}, hostingRole=master, ref=${ref.path}")
      ref
    }

    // Management API：Phase 2.1 不传 engine（engine 由 Singleton 托管），
    // 仅暴露 cluster routes。Phase 3 多任务调度时再补 task-aware engine routes。
    managementAPI <- Future {
      logger.info("Starting Management API (cluster-only routes)")
      val api = new CDCManagementAPI(
        cdcEngine    = None,
        host         = "0.0.0.0",
        port         = 8080,
        clusterProbe = Some(clusterProbe)
      )
      api.start()
      api
    }

    // 主进程挂起，等 ActorSystem 终止（由 shutdown hook 或 Singleton 出错 → system.terminate 触发）
    _ <- {
      logger.info(s"CDC node started (role=${cliArgs.role}); awaiting cluster termination")
      system.whenTerminated.map { _ =>
        logger.info("Stopping Management API")
        managementAPI.stop()
      }
    }

  } yield ()

  applicationFuture.onComplete {
    case Success(_) =>
      logger.info("CDC node terminated normally")
      system.terminate()

    case Failure(ex) =>
      logger.error(s"CDC node failed: ${ex.getMessage}", ex)
      system.terminate()
      System.exit(1)
  }
}
