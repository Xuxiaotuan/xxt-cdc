package cn.xuyinyin.cdc.cluster

import cn.xuyinyin.cdc.config.DatabaseConfig
import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager, SQLIntegrityConstraintViolationException, Timestamp}
import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Using}

/**
 * 基于关系数据库的 [[SingletonLock]] 实现（与 [[cn.xuyinyin.cdc.coordinator.MySQLOffsetStore]]
 * 保持同样的 DriverManager 直连风格）。
 *
 * SQL 设计原则（兼容 MySQL 与 H2 in-memory）：
 *   - 不依赖 `INSERT ... ON DUPLICATE KEY UPDATE`（H2 默认不支持）
 *   - 不依赖 `SELECT ... FOR UPDATE`（H2 in-memory 行锁支持有限）
 *   - 改用 `UPDATE ... WHERE (holder = self OR expires <= now)` + INSERT 兜底，
 *     PRIMARY KEY 自然串行化并发 INSERT
 *
 * 并发正确性：
 *   - UPDATE 是 atomic 的（行级锁）
 *   - 同时多个 caller 抢过期锁：DB 串行化，先更新者赢，后更新者 affected_rows=0 + INSERT 也因 PK 冲突失败
 *
 * 用法：
 * {{{
 * // 生产：从 metadata DatabaseConfig 派生
 * val lock = MySQLSingletonLock.fromConfig(cdcConfig.metadata)
 *
 * // 测试：H2 in-memory
 * val lock = MySQLSingletonLock.fromJdbcUrl("jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1")
 * }}}
 */
class MySQLSingletonLock private (
  connectionProvider: () => Connection,
  tableName: String
)(implicit ec: ExecutionContext)
    extends SingletonLock
    with LazyLogging {

  initializeTable()

  private def initializeTable(): Unit = {
    Try {
      Using.resource(connectionProvider()) { conn =>
        val ddl =
          s"""
             |CREATE TABLE IF NOT EXISTS $tableName (
             |  task_name      VARCHAR(255) NOT NULL,
             |  holder_address VARCHAR(255) NOT NULL,
             |  acquired_at    TIMESTAMP    NOT NULL,
             |  expires_at     TIMESTAMP    NOT NULL,
             |  PRIMARY KEY (task_name)
             |)
             |""".stripMargin
        Using.resource(conn.createStatement()) { stmt =>
          stmt.execute(ddl)
        }
        logger.info(s"Initialized singleton lock table: $tableName")
      }
    }.failed.foreach { ex =>
      logger.error(s"Failed to initialize singleton lock table $tableName: ${ex.getMessage}", ex)
    }
  }

  override def acquire(
    taskId: String,
    holderAddress: String,
    ttl: FiniteDuration
  )(implicit ec: ExecutionContext): Future[Boolean] = Future {
    val now       = Instant.now()
    val expiresAt = now.plusMillis(ttl.toMillis)

    Using.resource(connectionProvider()) { conn =>
      // 第 1 步：尝试 UPDATE（自己续约 / 抢占已过期锁）
      val updateSql =
        s"""
           |UPDATE $tableName
           |SET holder_address = ?, acquired_at = ?, expires_at = ?
           |WHERE task_name = ?
           |  AND (holder_address = ? OR expires_at <= ?)
           |""".stripMargin

      val updated = Using.resource(conn.prepareStatement(updateSql)) { stmt =>
        stmt.setString(1, holderAddress)
        stmt.setTimestamp(2, Timestamp.from(now))
        stmt.setTimestamp(3, Timestamp.from(expiresAt))
        stmt.setString(4, taskId)
        stmt.setString(5, holderAddress)
        stmt.setTimestamp(6, Timestamp.from(now))
        stmt.executeUpdate()
      }

      if (updated > 0) {
        logger.debug(s"[$taskId] Lock UPDATE acquired by $holderAddress (renew or seize-expired)")
        true
      } else {
        // 第 2 步：UPDATE 没改任何行 → 尝试 INSERT（新建）
        val insertSql =
          s"""
             |INSERT INTO $tableName (task_name, holder_address, acquired_at, expires_at)
             |VALUES (?, ?, ?, ?)
             |""".stripMargin
        try {
          Using.resource(conn.prepareStatement(insertSql)) { stmt =>
            stmt.setString(1, taskId)
            stmt.setString(2, holderAddress)
            stmt.setTimestamp(3, Timestamp.from(now))
            stmt.setTimestamp(4, Timestamp.from(expiresAt))
            stmt.executeUpdate()
          }
          logger.debug(s"[$taskId] Lock INSERT acquired by $holderAddress (new)")
          true
        } catch {
          case _: SQLIntegrityConstraintViolationException =>
            logger.debug(s"[$taskId] Lock held by another holder, $holderAddress backed off")
            false
          // H2 抛 JdbcSQLIntegrityConstraintViolationException —— 是 SQLIntegrityConstraintViolationException 子类
        }
      }
    }
  }

  override def renew(
    taskId: String,
    holderAddress: String,
    ttl: FiniteDuration
  )(implicit ec: ExecutionContext): Future[Boolean] = Future {
    val expiresAt = Instant.now().plusMillis(ttl.toMillis)
    Using.resource(connectionProvider()) { conn =>
      val sql =
        s"""
           |UPDATE $tableName
           |SET expires_at = ?
           |WHERE task_name = ? AND holder_address = ?
           |""".stripMargin
      val updated = Using.resource(conn.prepareStatement(sql)) { stmt =>
        stmt.setTimestamp(1, Timestamp.from(expiresAt))
        stmt.setString(2, taskId)
        stmt.setString(3, holderAddress)
        stmt.executeUpdate()
      }
      updated > 0
    }
  }

  override def release(
    taskId: String,
    holderAddress: String
  )(implicit ec: ExecutionContext): Future[Unit] = Future {
    Using.resource(connectionProvider()) { conn =>
      val sql = s"DELETE FROM $tableName WHERE task_name = ? AND holder_address = ?"
      Using.resource(conn.prepareStatement(sql)) { stmt =>
        stmt.setString(1, taskId)
        stmt.setString(2, holderAddress)
        stmt.executeUpdate()
      }
      ()
    }
  }

  override def currentHolder(taskId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    Future {
      Using.resource(connectionProvider()) { conn =>
        val sql = s"SELECT holder_address, expires_at FROM $tableName WHERE task_name = ?"
        Using.resource(conn.prepareStatement(sql)) { stmt =>
          stmt.setString(1, taskId)
          Using.resource(stmt.executeQuery()) { rs =>
            if (rs.next()) {
              val holder    = rs.getString("holder_address")
              val expiresAt = rs.getTimestamp("expires_at").toInstant
              if (expiresAt.isBefore(Instant.now())) None else Some(holder)
            } else {
              None
            }
          }
        }
      }
    }
}

object MySQLSingletonLock {

  /** 从 metadata DatabaseConfig 创建（生产环境）。 */
  def fromConfig(
    config: DatabaseConfig,
    tableName: String = "cdc_singleton_lock"
  )(implicit ec: ExecutionContext): MySQLSingletonLock = {
    val jdbcUrl = s"jdbc:mysql://${config.host}:${config.port}/${config.database}" +
      "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    val provider = () => DriverManager.getConnection(jdbcUrl, config.username, config.password)
    new MySQLSingletonLock(provider, tableName)
  }

  /** 从 jdbcUrl 直接创建（测试用 H2 等）。 */
  def fromJdbcUrl(
    jdbcUrl: String,
    user: String = "",
    password: String = "",
    tableName: String = "cdc_singleton_lock"
  )(implicit ec: ExecutionContext): MySQLSingletonLock = {
    val provider = () => DriverManager.getConnection(jdbcUrl, user, password)
    new MySQLSingletonLock(provider, tableName)
  }
}
