// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.on.sql

import java.io.Closeable

import com.daml.ledger.on.sql.queries.Queries.InvalidDatabaseException
import com.daml.ledger.on.sql.queries.{H2Queries, Queries, SqliteQueries}
import com.digitalasset.logging.{ContextualizedLogger, LoggingContext}
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

sealed trait Database extends Closeable {
  val queries: Queries

  val readerConnectionPool: DataSource

  val writerConnectionPool: DataSource
}

object Database {
  private val logger = ContextualizedLogger.get(classOf[Database])

  // This *must* be 1 right now. We need to insert entries into the log in order; otherwise, we
  // might end up dispatching (head + 2) before (head + 1), which will result in missing out an
  // event when reading the log.
  //
  // To be able to process commits in parallel, we will need to fail reads and retry if there are
  // entries missing.
  private val MaximumWriterConnectionPoolSize: Int = 1

  def apply(jdbcUrl: String)(implicit logCtx: LoggingContext): Database = {
    val database = jdbcUrl match {
      case url if url.startsWith("jdbc:h2:") => new MultipleReaderSingleWriterDatabase(jdbcUrl)
      case url if url.startsWith("jdbc:sqlite:") => new SingleConnectionDatabase(jdbcUrl)
      case _ => throw new InvalidDatabaseException(jdbcUrl)
    }
    logger.info(s"Connected to the ledger over JDBC: $jdbcUrl")
    database
  }

  final class MultipleReaderSingleWriterDatabase(jdbcUrl: String) extends Database {
    override val queries: Queries = new H2Queries

    override val readerConnectionPool: DataSource with Closeable =
      newHikariDataSource(jdbcUrl, maximumPoolSize = None)

    override val writerConnectionPool: DataSource with Closeable =
      newHikariDataSource(jdbcUrl, maximumPoolSize = Some(MaximumWriterConnectionPoolSize))

    override def close(): Unit = {
      readerConnectionPool.close()
      writerConnectionPool.close()
    }
  }

  final class SingleConnectionDatabase(jdbcUrl: String) extends Database {
    private val connectionPool: DataSource with Closeable =
      newHikariDataSource(jdbcUrl, maximumPoolSize = Some(MaximumWriterConnectionPoolSize))

    override val queries: Queries = new SqliteQueries

    override val readerConnectionPool: DataSource = connectionPool

    override val writerConnectionPool: DataSource = connectionPool

    override def close(): Unit = {
      connectionPool.close()
    }
  }

  private def newHikariDataSource(
      jdbcUrl: String,
      maximumPoolSize: Option[Int],
  ): HikariDataSource = {
    val pool = new HikariDataSource()
    pool.setAutoCommit(false)
    pool.setJdbcUrl(jdbcUrl)
    maximumPoolSize.foreach { maximumPoolSize =>
      pool.setMaximumPoolSize(maximumPoolSize)
    }
    pool
  }
}
