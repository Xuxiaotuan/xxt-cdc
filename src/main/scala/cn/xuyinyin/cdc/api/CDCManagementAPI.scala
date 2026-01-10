package cn.xuyinyin.cdc.api

import cn.xuyinyin.cdc.engine.CDCEngine
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.typesafe.scalalogging.LazyLogging
import spray.json._
import DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

// 自定义 JSON 格式化器，用于处理 Any 类型
object AnyJsonFormat extends DefaultJsonProtocol {
  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def write(x: Any): JsValue = x match {
      case n: Int => JsNumber(n)
      case n: Long => JsNumber(n)
      case n: Double => JsNumber(n)
      case n: BigDecimal => JsNumber(n)
      case s: String => JsString(s)
      case b: Boolean => JsBoolean(b)
      case null => JsNull
      case list: List[_] => JsArray(list.map(write).toVector)
      case seq: Seq[_] => JsArray(seq.map(write).toVector)
      case map: Map[_, _] => 
        JsObject(map.map { case (k, v) => k.toString -> write(v) })
      case other => JsString(other.toString)
    }
    
    def read(value: JsValue): Any = value match {
      case JsNumber(n) => n
      case JsString(s) => s
      case JsBoolean(b) => b
      case JsNull => null
      case JsArray(elements) => elements.map(read).toList
      case JsObject(fields) => fields.map { case (k, v) => k -> read(v) }
    }
  }
}

/**
 * CDC 管理 API 服务
 * 提供 HTTP API 接口查询系统状态和管理功能
 * 
 * @param cdcEngine CDC 引擎实例
 * @param host 绑定主机
 * @param port 绑定端口
 */
class CDCManagementAPI(
  cdcEngine: CDCEngine,
  host: String = "0.0.0.0",
  port: Int = 8080
)(implicit system: ActorSystem, ec: ExecutionContext) extends LazyLogging {

  // 定义 implicit JSON 格式化器
  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def write(x: Any): JsValue = x match {
      case n: Int => JsNumber(n)
      case n: Long => JsNumber(n)
      case n: Double => JsNumber(n)
      case n: BigDecimal => JsNumber(n)
      case s: String => JsString(s)
      case b: Boolean => JsBoolean(b)
      case null => JsNull
      case list: List[_] => JsArray(list.map(write).toVector)
      case seq: Seq[_] => JsArray(seq.map(write).toVector)
      case map: Map[_, _] => 
        JsObject(map.map { case (k, v) => k.toString -> write(v) })
      case other => JsString(other.toString)
    }
    
    def read(value: JsValue): Any = value match {
      case JsNumber(n) => n
      case JsString(s) => s
      case JsBoolean(b) => b
      case JsNull => null
      case JsArray(elements) => elements.map(read).toList
      case JsObject(fields) => fields.map { case (k, v) => k -> read(v) }
    }
  }

  private var bindingFuture: Option[Future[Http.ServerBinding]] = None

  /**
   * 启动 API 服务
   */
  def start(): Future[Http.ServerBinding] = {
    val routes = createRoutes()
    
    val binding = Http().newServerAt(host, port).bind(routes)
    bindingFuture = Some(binding)
    
    binding.onComplete {
      case Success(binding) =>
        logger.info(s"CDC Management API started at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}")
      case Failure(ex) =>
        logger.error(s"Failed to start CDC Management API: ${ex.getMessage}", ex)
    }
    
    binding
  }

  /**
   * 停止 API 服务
   */
  def stop(): Future[Unit] = {
    bindingFuture match {
      case Some(binding) =>
        binding.flatMap(_.unbind()).map { _ =>
          logger.info("CDC Management API stopped")
        }
      case None =>
        Future.successful(())
    }
  }

  private def createRoutes(): Route = {
    pathPrefix("api" / "v1") {
      concat(
        // 健康检查
        path("health") {
          get {
            val healthStatus = cdcEngine.getHealthStatus()
            val json = Map[String, Any](
              "status" -> healthStatus.status.toString,
              "timestamp" -> healthStatus.timestamp.toString,
              "checks" -> healthStatus.checks.map(check => Map[String, Any](
                "name" -> check.name,
                "status" -> check.status.toString,
                "message" -> check.message
              ))
            ).toJson
            
            val statusCode = healthStatus.status match {
              case cn.xuyinyin.cdc.health.HealthStatus.Healthy => StatusCodes.OK
              case cn.xuyinyin.cdc.health.HealthStatus.Warning => StatusCodes.OK
              case cn.xuyinyin.cdc.health.HealthStatus.Unhealthy => StatusCodes.ServiceUnavailable
              case _ => StatusCodes.OK
            }
            
            complete(statusCode, HttpEntity(ContentTypes.`application/json`, json.prettyPrint))
          }
        },
        
        // 系统状态
        path("status") {
          get {
            val state = cdcEngine.getCurrentState()
            val json = Map[String, Any](
              "state" -> state.name,
              "isRunning" -> true,
              "uptime" -> System.currentTimeMillis()
            ).toJson
            
            complete(HttpEntity(ContentTypes.`application/json`, json.prettyPrint))
          }
        },
        
        // 指标信息
        path("metrics") {
          get {
            complete(getMetrics())
          }
        },
        
        // 组件状态
        path("components") {
          get {
            complete(getComponentStatus())
          }
        },
        
        // 热表集信息
        path("hotset") {
          get {
            complete(getHotSetInfo())
          }
        },
        
        // 表活动详情
        path("tables" / Segment / "activity") { tableId =>
          get {
            complete(getTableActivity(tableId))
          }
        },
        
        // 配置信息（只读）
        path("config") {
          get {
            complete(getConfigInfo())
          }
        }
      )
    } ~
    // 根路径重定向到健康检查
    pathSingleSlash {
      redirect("/api/v1/health", StatusCodes.MovedPermanently)
    }
  }

  private def getMetrics(): HttpEntity.Strict = {
    val metrics = cdcEngine.getMetrics()
    val json = metrics.toMap.asInstanceOf[Map[String, Any]].toJson
    
    HttpEntity(ContentTypes.`application/json`, json.prettyPrint)
  }

  private def getComponentStatus(): HttpEntity.Strict = {
    val status = cdcEngine.getComponentStatus()
    val json = status.asInstanceOf[Map[String, Any]].toJson
    
    HttpEntity(ContentTypes.`application/json`, json.prettyPrint)
  }

  private def getHotSetInfo(): HttpEntity.Strict = {
    // 这里需要从 CDC Engine 获取热表集信息
    // 简化实现，返回基本信息
    val info = Map[String, Any](
      "enabled" -> true,
      "size" -> 0,
      "maxSize" -> 10000,
      "utilizationRate" -> "0.00%"
    ).toJson
    
    HttpEntity(ContentTypes.`application/json`, info.prettyPrint)
  }

  private def getTableActivity(tableId: String): HttpEntity.Strict = {
    // 解析 tableId (格式: database.table)
    val parts = tableId.split("\\.", 2)
    if (parts.length != 2) {
      val error = Map[String, Any]("error" -> "Invalid table ID format, expected: database.table").toJson
      return HttpEntity(ContentTypes.`application/json`, error.prettyPrint)
    }
    
    val activity = Map[String, Any](
      "tableId" -> tableId,
      "isInHotSet" -> false,
      "lastActivity" -> "N/A",
      "activityCount" -> 0,
      "totalEvents" -> 0
    ).toJson
    
    HttpEntity(ContentTypes.`application/json`, activity.prettyPrint)
  }

  private def getConfigInfo(): HttpEntity.Strict = {
    // 返回非敏感的配置信息
    val config = Map[String, Any](
      "parallelism" -> Map[String, Any](
        "partitionCount" -> 64,
        "applyWorkerCount" -> 8,
        "batchSize" -> 100
      ),
      "hotSet" -> Map[String, Any](
        "enabled" -> true,
        "maxHotSetSize" -> 10000
      ),
      "filter" -> Map[String, Any](
        "excludeDatabases" -> List("information_schema", "mysql", "performance_schema", "sys")
      )
    ).toJson
    
    HttpEntity(ContentTypes.`application/json`, config.prettyPrint)
  }
}

object CDCManagementAPI {
  /**
   * 创建 CDC Management API 实例
   */
  def apply(
    cdcEngine: CDCEngine,
    host: String = "0.0.0.0",
    port: Int = 8080
  )(implicit system: ActorSystem, ec: ExecutionContext): CDCManagementAPI = {
    new CDCManagementAPI(cdcEngine, host, port)
  }
}