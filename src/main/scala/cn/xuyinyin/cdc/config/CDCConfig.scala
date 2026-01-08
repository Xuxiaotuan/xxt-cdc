package cn.xuyinyin.cdc.config

import scala.concurrent.duration.FiniteDuration

/**
 * CDC 引擎配置
 * 
 * @param source 源数据库配置
 * @param target 目标数据库配置
 * @param filter 表过滤配置
 * @param parallelism 并行度配置
 * @param offset 偏移量配置
 */
case class CDCConfig(
  source: DatabaseConfig,
  target: DatabaseConfig,
  filter: FilterConfig,
  parallelism: ParallelismConfig,
  offset: OffsetConfig
)

/**
 * 数据库连接配置
 * 
 * @param host 主机地址
 * @param port 端口
 * @param username 用户名
 * @param password 密码
 * @param database 数据库名
 * @param connectionPool 连接池配置
 */
case class DatabaseConfig(
  host: String,
  port: Int,
  username: String,
  password: String,
  database: String,
  connectionPool: ConnectionPoolConfig
)

/**
 * 连接池配置
 * 
 * @param maxPoolSize 最大连接数
 * @param minIdle 最小空闲连接数
 * @param connectionTimeout 连接超时时间
 */
case class ConnectionPoolConfig(
  maxPoolSize: Int = 10,
  minIdle: Int = 2,
  connectionTimeout: FiniteDuration
)

/**
 * 表过滤配置
 * 
 * @param includeDatabases 包含的数据库列表
 * @param excludeDatabases 排除的数据库列表
 * @param includeTablePatterns 包含的表名模式列表
 * @param excludeTablePatterns 排除的表名模式列表
 */
case class FilterConfig(
  includeDatabases: Seq[String] = Seq.empty,
  excludeDatabases: Seq[String] = Seq.empty,
  includeTablePatterns: Seq[String] = Seq.empty,
  excludeTablePatterns: Seq[String] = Seq.empty
)

/**
 * 并行度配置
 * 
 * @param partitionCount 分区数量
 * @param applyWorkerCount Apply Worker 数量
 * @param snapshotWorkerCount Snapshot Worker 数量
 * @param batchSize 批处理大小
 * @param flushInterval 刷新间隔
 */
case class ParallelismConfig(
  partitionCount: Int = 64,
  applyWorkerCount: Int = 8,
  snapshotWorkerCount: Int = 4,
  batchSize: Int = 100,
  flushInterval: FiniteDuration
)

/**
 * 偏移量配置
 * 
 * @param storeType 存储类型
 * @param commitInterval 提交间隔
 * @param storeConfig 存储配置
 */
case class OffsetConfig(
  storeType: OffsetStoreType,
  commitInterval: FiniteDuration,
  storeConfig: Map[String, String] = Map.empty
)

/**
 * 偏移量存储类型
 */
sealed trait OffsetStoreType
case object MySQLOffsetStore extends OffsetStoreType
case object FileOffsetStore extends OffsetStoreType
