package io.getquill.jdbczio

import com.typesafe.config.Config
import io.getquill._
import io.getquill.context.ZioJdbc
import io.getquill.context.ZioJdbc.scopedBestEffort
import io.getquill.context.jdbc._
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.util.LoadConfig
import zio.{ Tag, ZIO, ZLayer }

import java.io.Closeable
import java.sql.{ Connection, SQLException }
import javax.sql.DataSource

object Quill {
  case class Postgres[+N <: NamingStrategy](naming: N, override val ds: DataSource)
    extends Quill[PostgresDialect, N] with PostgresJdbcTypes[N] {
    val dsDelegate = new PostgresZioJdbcContext[N](naming)
  }

  object Postgres {
    def fromNamingStrategy[N <: NamingStrategy: Tag](naming: N): ZLayer[javax.sql.DataSource, Nothing, Postgres[N]] =
      ZLayer.fromFunction((ds: javax.sql.DataSource) => new Postgres[N](naming, ds))
  }

  case class SqlServer[+N <: NamingStrategy](naming: N, override val ds: DataSource)
    extends Quill[SQLServerDialect, N] with SqlServerJdbcTypes[N] {
    val dsDelegate = new SqlServerZioJdbcContext[N](naming)
  }
  object SqlServer {
    def fromNamingStrategy[N <: NamingStrategy: Tag](naming: N): ZLayer[javax.sql.DataSource, Nothing, SqlServer[N]] =
      ZLayer.fromFunction((ds: javax.sql.DataSource) => new SqlServer[N](naming, ds))
  }

  case class H2[+N <: NamingStrategy](naming: N, override val ds: DataSource)
    extends Quill[H2Dialect, N] with H2JdbcTypes[N] {
    val dsDelegate = new H2ZioJdbcContext[N](naming)
  }
  object H2 {
    def fromNamingStrategy[N <: NamingStrategy: Tag](naming: N): ZLayer[javax.sql.DataSource, Nothing, H2[N]] =
      ZLayer.fromFunction((ds: javax.sql.DataSource) => new H2[N](naming, ds))
  }

  case class Mysql[+N <: NamingStrategy](naming: N, override val ds: DataSource)
    extends Quill[MySQLDialect, N] with MysqlJdbcTypes[N] {
    val dsDelegate = new MysqlZioJdbcContext[N](naming)
  }
  object Mysql {
    def fromNamingStrategy[N <: NamingStrategy: Tag](naming: N): ZLayer[javax.sql.DataSource, Nothing, Mysql[N]] =
      ZLayer.fromFunction((ds: javax.sql.DataSource) => new Mysql[N](naming, ds))
  }

  case class Sqlite[+N <: NamingStrategy](naming: N, override val ds: DataSource)
    extends Quill[SqliteDialect, N] with SqliteJdbcTypes[N] {
    val dsDelegate = new SqliteZioJdbcContext[N](naming)
  }
  object Sqlite {
    def fromNamingStrategy[N <: NamingStrategy: Tag](naming: N): ZLayer[javax.sql.DataSource, Nothing, Sqlite[N]] =
      ZLayer.fromFunction((ds: javax.sql.DataSource) => new Sqlite[N](naming, ds))
  }

  case class Oracle[+N <: NamingStrategy](naming: N, override val ds: DataSource)
    extends Quill[OracleDialect, N] with OracleJdbcTypes[N] {
    val dsDelegate = new OracleZioJdbcContext[N](naming)
  }
  object Oracle {
    def fromNamingStrategy[N <: NamingStrategy: Tag](naming: N): ZLayer[javax.sql.DataSource, Nothing, Oracle[N]] =
      ZLayer.fromFunction((ds: javax.sql.DataSource) => new Oracle[N](naming, ds))
  }

  object Connection {
    def acquireScoped: ZLayer[DataSource, SQLException, Connection] =
      ZLayer.scoped {
        for {
          blockingExecutor <- ZIO.blockingExecutor
          ds <- ZIO.service[DataSource]
          r <- ZioJdbc.scopedBestEffort(ZIO.attempt(ds.getConnection)).refineToOrDie[SQLException].onExecutor(blockingExecutor)
        } yield r
      }
  }

  object DataSource {
    def fromDataSource(ds: => DataSource): ZLayer[Any, Throwable, DataSource] =
      ZLayer.fromZIO(ZIO.attempt(ds))

    def fromConfig(config: => Config): ZLayer[Any, Throwable, DataSource] =
      fromConfigClosable(config)

    def fromPrefix(prefix: String): ZLayer[Any, Throwable, DataSource] =
      fromPrefixClosable(prefix)

    def fromJdbcConfig(jdbcContextConfig: => JdbcContextConfig): ZLayer[Any, Throwable, DataSource] =
      fromJdbcConfigClosable(jdbcContextConfig)

    def fromConfigClosable(config: => Config): ZLayer[Any, Throwable, DataSource with Closeable] =
      fromJdbcConfigClosable(JdbcContextConfig(config))

    def fromPrefixClosable(prefix: String): ZLayer[Any, Throwable, DataSource with Closeable] =
      fromJdbcConfigClosable(JdbcContextConfig(LoadConfig(prefix)))

    def fromJdbcConfigClosable(jdbcContextConfig: => JdbcContextConfig): ZLayer[Any, Throwable, DataSource with Closeable] =
      ZLayer.scoped {
        for {
          conf <- ZIO.attempt(jdbcContextConfig)
          ds <- scopedBestEffort(ZIO.attempt(conf.dataSource))
        } yield ds
      }
  }
}

trait Quill[+Dialect <: SqlIdiom, +Naming <: NamingStrategy] extends QuillBaseContext[Dialect, Naming]
