package cn.xuyinyin.cdc

import cn.xuyinyin.cdc.api.CDCManagementAPI
import cn.xuyinyin.cdc.config.{ConfigLoader, ConfigValidator}
import cn.xuyinyin.cdc.engine.CDCEngine
import com.typesafe.scalalogging.LazyLogging
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * CDC 应用程序主入口
 */
object CDCApplication extends App with LazyLogging {

  // 解析命令行参数
  val configFile = args.headOption

  logger.info("Starting MySQL CDC Engine")

  // 创建 Actor System
  implicit val system: ActorSystem                = ActorSystem("cdc-system")
  implicit val materializer: Materializer         = Materializer(system)
  implicit val executionContext: ExecutionContext = system.dispatcher

  // 注册关闭钩子
  sys.addShutdownHook {
    logger.info("Shutting down CDC Engine")
    system.terminate()
  }

  // 启动应用程序
  private val applicationFuture = for {
    config <- Future {
      configFile match {
        case Some(file) =>
          logger.info(s"Loading configuration from file: $file")
          ConfigLoader.loadFromFile(file)
        case None =>
          logger.info("Loading configuration from application.conf")
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

    engine <- Future {
      logger.info("Creating CDC Engine")
      CDCEngine(config)
    }

    // 启动管理 API
    managementAPI <- Future {
      logger.info("Starting Management API")
      val api = CDCManagementAPI(engine, "0.0.0.0", 8080)
      api.start()
      api
    }

    _ <- {
      logger.info("Starting CDC Engine")
      engine.start()
    }

    _ <- {
      logger.info("CDC Engine started successfully, waiting for termination")
      engine.awaitTermination().andThen { case _ =>
        logger.info("Stopping Management API")
        managementAPI.stop()
      }
    }

  } yield ()

  // 处理应用程序结果
  applicationFuture.onComplete {
    case Success(_) =>
      logger.info("CDC Engine terminated successfully")
      system.terminate()

    case Failure(ex) =>
      logger.error(s"CDC Engine failed: ${ex.getMessage}", ex)
      system.terminate()
      System.exit(1)
  }
}
