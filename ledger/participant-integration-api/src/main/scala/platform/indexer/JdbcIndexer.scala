// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.indexer

import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{Flow, Keep, Sink}
import com.daml.ledger.api.domain
import com.daml.ledger.api.domain.ParticipantId
import com.daml.ledger.participant.state.v1.Update.TransactionAccepted
import com.daml.ledger.participant.state.v1._
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.lf.data.Ref
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.metrics.{Metrics, Timed}
import com.daml.platform.ApiOffset.ApiOffsetConverter
import com.daml.platform.common
import com.daml.platform.common.MismatchException
import com.daml.platform.configuration.ServerRole
import com.daml.platform.indexer.OffsetUpdate.{
  MetadataUpdateStep,
  OffsetStepUpdatePair,
  PreparedUpdate,
}
import com.daml.platform.store.{DbType, FlywayMigrations}
import com.daml.platform.store.dao.events.{LfValueTranslation, TransactionsWriter}
import com.daml.platform.store.dao.{JdbcLedgerDao, LedgerDao}

import scala.concurrent.Future
import scala.util.control.NonFatal

object JdbcIndexer {

  private[daml] final class Factory(
      serverRole: ServerRole,
      config: IndexerConfig,
      readService: ReadService,
      metrics: Metrics,
      lfValueTranslationCache: LfValueTranslation.Cache,
      prepareTransactionInsert: PrepareTransactionInsert.Type = PrepareTransactionInsert(),
  )(implicit materializer: Materializer, loggingContext: LoggingContext) {

    private val logger = ContextualizedLogger.get(this.getClass)

    def validateSchema()(implicit
        resourceContext: ResourceContext
    ): Future[ResourceOwner[JdbcIndexer]] =
      new FlywayMigrations(config.jdbcUrl)
        .validate()
        .flatMap(_ => initialized(resetSchema = false))(resourceContext.executionContext)

    def migrateSchema(
        allowExistingSchema: Boolean
    )(implicit resourceContext: ResourceContext): Future[ResourceOwner[JdbcIndexer]] =
      new FlywayMigrations(config.jdbcUrl)
        .migrate(allowExistingSchema)
        .flatMap(_ => initialized(resetSchema = false))(resourceContext.executionContext)

    def resetSchema(): Future[ResourceOwner[JdbcIndexer]] = initialized(resetSchema = true)

    private def initialized(resetSchema: Boolean): Future[ResourceOwner[JdbcIndexer]] =
      Future.successful(for {
        ledgerDao <- JdbcLedgerDao.writeOwner(
          serverRole,
          config.jdbcUrl,
          config.eventsPageSize,
          metrics,
          lfValueTranslationCache,
          jdbcAsyncCommits = true,
        )
        _ <-
          if (resetSchema) ResourceOwner.forFuture(() => ledgerDao.reset()) else ResourceOwner.unit
        initialLedgerEnd <- initializeLedger(ledgerDao)()
        dbType = DbType.jdbcType(config.jdbcUrl)
        transactionsWriter = new TransactionsWriter(
          dbType,
          metrics,
          new LfValueTranslation(lfValueTranslationCache),
        )
        prepareUpdate = buildPrepareUpdate(transactionsWriter)
        transactionIndexer <- updateIndexerOwner(ledgerDao, dbType)
      } yield new JdbcIndexer(initialLedgerEnd, metrics, transactionIndexer, prepareUpdate))

    private def buildPrepareUpdate(transactionsWriter: TransactionsWriter) =
      Flow[OffsetStepUpdatePair[Update]]
        .mapAsync(1) {
          case OffsetStepUpdatePair(offsetStep, tx: TransactionAccepted) =>
            Timed.future(
              metrics.daml.index.db.storeTransactionDbMetrics.prepareBatches,
              Future {
                OffsetUpdate.PreparedTransactionInsert(
                  offsetStep = offsetStep,
                  update = tx,
                  preparedInsert = prepareTransactionInsert(transactionsWriter, tx, offsetStep),
                )
              }(materializer.executionContext),
            )
          case OffsetStepUpdatePair(offsetStep, update: MetadataUpdate) =>
            Future.successful(MetadataUpdateStep(offsetStep, update))
        }

    private def updateIndexerOwner(ledgerDao: LedgerDao, dbType: DbType) =
      dbType match {
        case DbType.Postgres =>
          PipelinedUpdateIndexer.owner(ledgerDao, metrics, config.participantId)
        case DbType.H2Database =>
          AtomicUpdateIndexer.owner(ledgerDao, metrics, config.participantId)
      }

    private def initializeLedger(dao: LedgerDao)(): ResourceOwner[Option[Offset]] =
      new ResourceOwner[Option[Offset]] {
        override def acquire()(implicit context: ResourceContext): Resource[Option[Offset]] =
          Resource.fromFuture(for {
            initialConditions <- readService.getLedgerInitialConditions().runWith(Sink.head)
            existingLedgerId <- dao.lookupLedgerId()
            providedLedgerId = domain.LedgerId(initialConditions.ledgerId)
            _ <- existingLedgerId.fold(initializeLedgerData(providedLedgerId, dao))(
              checkLedgerIds(_, providedLedgerId)
            )
            _ <- initOrCheckParticipantId(dao)
            initialLedgerEnd <- dao.lookupInitialLedgerEnd()
          } yield initialLedgerEnd)
      }

    private def checkLedgerIds(
        existingLedgerId: domain.LedgerId,
        providedLedgerId: domain.LedgerId,
    ): Future[Unit] =
      if (existingLedgerId == providedLedgerId) {
        logger.info(s"Found existing ledger with ID: $existingLedgerId")
        Future.unit
      } else {
        Future.failed(new MismatchException.LedgerId(existingLedgerId, providedLedgerId))
      }

    private def initializeLedgerData(
        providedLedgerId: domain.LedgerId,
        ledgerDao: LedgerDao,
    ): Future[Unit] = {
      logger.info(s"Initializing ledger with ID: $providedLedgerId")
      ledgerDao.initializeLedger(providedLedgerId)
    }

    private def initOrCheckParticipantId(
        dao: LedgerDao
    )(implicit resourceContext: ResourceContext): Future[Unit] = {
      val id = ParticipantId(Ref.ParticipantId.assertFromString(config.participantId))
      dao
        .lookupParticipantId()
        .flatMap(
          _.fold(dao.initializeParticipantId(id)) {
            case `id` =>
              Future.successful(logger.info(s"Found existing participant id '$id'"))
            case retrievedLedgerId =>
              Future.failed(new common.MismatchException.ParticipantId(retrievedLedgerId, id))
          }
        )(resourceContext.executionContext)
    }

  }

  private val logger = ContextualizedLogger.get(classOf[JdbcIndexer])
}

/** @param startExclusive The last offset received from the read service.
  */
private[daml] class JdbcIndexer private[indexer] (
    startExclusive: Option[Offset],
    metrics: Metrics,
    executeUpdateFlow: Flow[PreparedUpdate, Unit, NotUsed],
    prepareUpdateFlow: Flow[OffsetStepUpdatePair[Update], PreparedUpdate, NotUsed],
)(implicit mat: Materializer, loggingContext: LoggingContext)
    extends Indexer {

  import JdbcIndexer.logger

  override def subscription(readService: ReadService): ResourceOwner[IndexFeedHandle] =
    new SubscriptionResourceOwner(readService)

  private def handleStateUpdate(implicit
      loggingContext: LoggingContext
  ): Flow[OffsetStepUpdatePair[Update], Unit, NotUsed] =
    Flow[OffsetStepUpdatePair[Update]]
      .wireTap(Sink.foreach[OffsetUpdate[Update]] { case OffsetUpdate(offsetStep, update) =>
        val lastReceivedRecordTime = update.recordTime.toInstant.toEpochMilli

        logger.trace(update.description)

        metrics.daml.indexer.lastReceivedRecordTime.updateValue(lastReceivedRecordTime)
        metrics.daml.indexer.lastReceivedOffset.updateValue(offsetStep.offset.toApiString)
      })
      .via(prepareUpdateFlow)
      .via(executeUpdateFlow)
      .map(_ => ())

  private def zipWithPreviousOffset(
      initialOffset: Option[Offset]
  ): Flow[(Offset, Update), OffsetStepUpdatePair[Update], NotUsed] =
    Flow[(Offset, Update)]
      .statefulMapConcat { () =>
        val previousOffsetRef = new AtomicReference(initialOffset)

        { offsetUpdateTuple: (Offset, Update) =>
          val (nextOffset, update) = offsetUpdateTuple
          val offsetStep =
            previousOffsetRef
              .getAndSet(Some(nextOffset))
              .map(IncrementalOffsetStep(_, nextOffset))
              .getOrElse(CurrentOffset(nextOffset))

          OffsetStepUpdatePair(offsetStep, update) :: Nil
        }
      }

  private class SubscriptionResourceOwner(
      readService: ReadService
  )(implicit loggingContext: LoggingContext)
      extends ResourceOwner[IndexFeedHandle] {
    override def acquire()(implicit context: ResourceContext): Resource[IndexFeedHandle] =
      Resource(Future {
        val (killSwitch, completionFuture) = readService
          .stateUpdates(startExclusive)
          .viaMat(KillSwitches.single)(Keep.right[NotUsed, UniqueKillSwitch])
          .via(zipWithPreviousOffset(startExclusive))
          .via(handleStateUpdate)
          .toMat(Sink.ignore)(Keep.both)
          .run()

        new SubscriptionIndexFeedHandle(killSwitch, completionFuture.map(_ => ()))
      })(handle =>
        for {
          _ <- Future(handle.killSwitch.shutdown())
          _ <- handle.completed.recover { case NonFatal(_) => () }
        } yield ()
      )
  }

  private class SubscriptionIndexFeedHandle(
      val killSwitch: KillSwitch,
      override val completed: Future[Unit],
  ) extends IndexFeedHandle

}
