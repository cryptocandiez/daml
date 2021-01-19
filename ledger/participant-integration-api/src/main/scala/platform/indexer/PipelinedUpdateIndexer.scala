package com.daml.platform.indexer

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.daml.daml_lf_dev.DamlLf
import com.daml.ledger.api.domain
import com.daml.ledger.participant.state.index.v2
import com.daml.ledger.participant.state.v1
import com.daml.ledger.participant.state.v1.Update._
import com.daml.ledger.participant.state.v1.{MetadataUpdate, Offset, Party, Update}
import com.daml.ledger.resources.ResourceOwner
import com.daml.logging.LoggingContext
import com.daml.logging.LoggingContext.withEnrichedLoggingContext
import com.daml.metrics.{Metrics, Timed}
import com.daml.platform.indexer.OffsetUpdate.{
  MetadataUpdateStep,
  OffsetStepUpdatePair,
  PreparedTransactionInsert,
  PreparedUpdate,
}
import com.daml.platform.indexer.UpdateIndexer.ExecuteUpdateFlow
import com.daml.platform.store.DbType
import com.daml.platform.store.dao.{LedgerDao, PersistenceResponse}
import com.daml.platform.store.entries.{PackageLedgerEntry, PartyLedgerEntry}

import scala.concurrent.{ExecutionContext, Future}

object UpdateIndexer {
  type ExecuteUpdateFlow = Flow[OffsetStepUpdatePair[Update], Unit, NotUsed]
  type FlowOwnerBuilder =
    (
        DbType,
        LedgerDao,
        Metrics,
        v1.ParticipantId,
        ExecutionContext,
        LoggingContext,
    ) => ResourceOwner[ExecuteUpdateFlow]

  def ownerBuilder(
      dbType: DbType,
      ledgerDao: LedgerDao,
      metrics: Metrics,
      participantId: v1.ParticipantId,
      executionContext: ExecutionContext,
      loggingContext: LoggingContext,
  ): ResourceOwner[ExecuteUpdateFlow] =
    dbType match {
      case DbType.Postgres =>
        PipelinedUpdateIndexer.owner(
          ledgerDao,
          metrics,
          participantId,
          executionContext,
          loggingContext,
        )
      case DbType.H2Database =>
        AtomicUpdateIndexer.owner(ledgerDao, metrics, participantId)(
          loggingContext,
          executionContext,
        )
    }
}

trait UpdateIndexer {
  implicit val loggingContext: LoggingContext
  implicit val executionContext: ExecutionContext

  def participantId: v1.ParticipantId

  def ledgerDao: LedgerDao

  def flow: Flow[OffsetStepUpdatePair[Update], Unit, NotUsed]

  def metrics: Metrics

  val prepareUpdate: OffsetStepUpdatePair[Update] => Future[PreparedUpdate] = {
    case OffsetStepUpdatePair(offsetStep, tx: TransactionAccepted) =>
      Timed.future(
        metrics.daml.index.db.storeTransactionDbMetrics.prepareBatches,
        Future {
          OffsetUpdate.PreparedTransactionInsert(
            offsetStep = offsetStep,
            update = tx,
            preparedInsert = ledgerDao.prepareTransactionInsert(
              submitterInfo = tx.optSubmitterInfo,
              workflowId = tx.transactionMeta.workflowId,
              transactionId = tx.transactionId,
              ledgerEffectiveTime = tx.transactionMeta.ledgerEffectiveTime.toInstant,
              offset = offsetStep.offset,
              transaction = tx.transaction,
              divulgedContracts = tx.divulgedContracts,
              blindingInfo = tx.blindingInfo,
            ),
          )
        },
      )
    case OffsetStepUpdatePair(offsetStep, update: MetadataUpdate) =>
      Future.successful(MetadataUpdateStep(offsetStep, update))
  }

  protected[UpdateIndexer] def updateMetadata(
      metadataUpdateStep: MetadataUpdateStep
  ): Future[PersistenceResponse] = {
    val MetadataUpdateStep(offsetStep, update) = metadataUpdateStep
    update match {
      case PartyAddedToParticipant(
            party,
            displayName,
            hostingParticipantId,
            recordTime,
            submissionId,
          ) =>
        val entry = PartyLedgerEntry.AllocationAccepted(
          submissionId,
          recordTime.toInstant,
          domain.PartyDetails(party, Some(displayName), participantId == hostingParticipantId),
        )
        ledgerDao.storePartyEntry(offsetStep, entry)

      case PartyAllocationRejected(
            submissionId,
            _,
            recordTime,
            rejectionReason,
          ) =>
        val entry = PartyLedgerEntry.AllocationRejected(
          submissionId,
          recordTime.toInstant,
          rejectionReason,
        )
        ledgerDao.storePartyEntry(offsetStep, entry)

      case PublicPackageUpload(archives, optSourceDescription, recordTime, optSubmissionId) =>
        val recordTimeInstant = recordTime.toInstant
        val packages: List[(DamlLf.Archive, v2.PackageDetails)] = archives.map(archive =>
          archive -> v2.PackageDetails(
            size = archive.getPayload.size.toLong,
            knownSince = recordTimeInstant,
            sourceDescription = optSourceDescription,
          )
        )
        val optEntry: Option[PackageLedgerEntry] =
          optSubmissionId.map(submissionId =>
            PackageLedgerEntry.PackageUploadAccepted(submissionId, recordTimeInstant)
          )
        ledgerDao.storePackageEntry(offsetStep, packages, optEntry)

      case PublicPackageUploadRejected(submissionId, recordTime, rejectionReason) =>
        val entry = PackageLedgerEntry.PackageUploadRejected(
          submissionId,
          recordTime.toInstant,
          rejectionReason,
        )
        ledgerDao.storePackageEntry(offsetStep, List.empty, Some(entry))

      case config: ConfigurationChanged =>
        ledgerDao.storeConfigurationEntry(
          offsetStep,
          config.recordTime.toInstant,
          config.submissionId,
          config.newConfiguration,
          None,
        )

      case configRejection: ConfigurationChangeRejected =>
        ledgerDao.storeConfigurationEntry(
          offsetStep,
          configRejection.recordTime.toInstant,
          configRejection.submissionId,
          configRejection.proposedConfiguration,
          Some(configRejection.rejectionReason),
        )

      case CommandRejected(recordTime, submitterInfo, reason) =>
        ledgerDao.storeRejection(Some(submitterInfo), recordTime.toInstant, offsetStep, reason)
    }
  }

  protected[UpdateIndexer] def loggingContextFor(
      offset: Offset,
      update: Update,
  ): Map[String, String] =
    loggingContextFor(update)
      .updated("updateRecordTime", update.recordTime.toInstant.toString)
      .updated("updateOffset", offset.toHexString)

  protected[UpdateIndexer] def loggingContextFor(update: Update): Map[String, String] =
    update match {
      case ConfigurationChanged(_, submissionId, participantId, newConfiguration) =>
        Map(
          "updateSubmissionId" -> submissionId,
          "updateParticipantId" -> participantId,
          "updateConfigGeneration" -> newConfiguration.generation.toString,
          "updatedMaxDeduplicationTime" -> newConfiguration.maxDeduplicationTime.toString,
        )
      case ConfigurationChangeRejected(
            _,
            submissionId,
            participantId,
            proposedConfiguration,
            rejectionReason,
          ) =>
        Map(
          "updateSubmissionId" -> submissionId,
          "updateParticipantId" -> participantId,
          "updateConfigGeneration" -> proposedConfiguration.generation.toString,
          "updatedMaxDeduplicationTime" -> proposedConfiguration.maxDeduplicationTime.toString,
          "updateRejectionReason" -> rejectionReason,
        )
      case PartyAddedToParticipant(party, displayName, participantId, _, submissionId) =>
        Map(
          "updateSubmissionId" -> submissionId.getOrElse(""),
          "updateParticipantId" -> participantId,
          "updateParty" -> party,
          "updateDisplayName" -> displayName,
        )
      case PartyAllocationRejected(submissionId, participantId, _, rejectionReason) =>
        Map(
          "updateSubmissionId" -> submissionId,
          "updateParticipantId" -> participantId,
          "updateRejectionReason" -> rejectionReason,
        )
      case PublicPackageUpload(_, sourceDescription, _, submissionId) =>
        Map(
          "updateSubmissionId" -> submissionId.getOrElse(""),
          "updateSourceDescription" -> sourceDescription.getOrElse(""),
        )
      case PublicPackageUploadRejected(submissionId, _, rejectionReason) =>
        Map(
          "updateSubmissionId" -> submissionId,
          "updateRejectionReason" -> rejectionReason,
        )
      case TransactionAccepted(optSubmitterInfo, transactionMeta, _, transactionId, _, _, _) =>
        Map(
          "updateTransactionId" -> transactionId,
          "updateLedgerTime" -> transactionMeta.ledgerEffectiveTime.toInstant.toString,
          "updateWorkflowId" -> transactionMeta.workflowId.getOrElse(""),
          "updateSubmissionTime" -> transactionMeta.submissionTime.toInstant.toString,
        ) ++ optSubmitterInfo
          .map(info =>
            Map(
              "updateSubmitter" -> loggingContextPartiesValue(info.actAs),
              "updateApplicationId" -> info.applicationId,
              "updateCommandId" -> info.commandId,
              "updateDeduplicateUntil" -> info.deduplicateUntil.toString,
            )
          )
          .getOrElse(Map.empty)
      case CommandRejected(_, submitterInfo, reason) =>
        Map(
          "updateSubmitter" -> loggingContextPartiesValue(submitterInfo.actAs),
          "updateApplicationId" -> submitterInfo.applicationId,
          "updateCommandId" -> submitterInfo.commandId,
          "updateDeduplicateUntil" -> submitterInfo.deduplicateUntil.toString,
          "updateRejectionReason" -> reason.description,
        )
    }

  protected[UpdateIndexer] def loggingContextPartiesValue(parties: List[Party]): String =
    parties.mkString("[", ", ", "]")
}

class PipelinedUpdateIndexer(
    val ledgerDao: LedgerDao,
    val metrics: Metrics,
    val participantId: v1.ParticipantId,
)(implicit val executionContext: ExecutionContext, val loggingContext: LoggingContext)
    extends UpdateIndexer {

  override def flow: Flow[OffsetStepUpdatePair[Update], Unit, NotUsed] =
    Flow[OffsetStepUpdatePair[Update]]
      .mapAsync(1)(prepareUpdate)
      .mapAsync(1)(insertTransactionState)
      .mapAsync(1)(insertTransactionEvents)
      .mapAsync(1) { case offsetUpdate @ OffsetUpdate(offsetStep, update) =>
        withEnrichedLoggingContext(loggingContextFor(offsetStep.offset, update)) {
          implicit loggingContext =>
            Timed.future(
              metrics.daml.indexer.stateUpdateProcessing,
              executeUpdate(offsetUpdate),
            )
        }
      }
      .map(_ => ())

  private val insertTransactionState: PreparedUpdate => Future[PreparedUpdate] = {
    case offsetUpdate @ PreparedTransactionInsert(_, _, preparedInsert) =>
      Timed.future(
        metrics.daml.index.db.storeTransactionState,
        ledgerDao.storeTransactionState(preparedInsert).map(_ => offsetUpdate),
      )
    case offsetUpdate => Future.successful(offsetUpdate)
  }

  private val insertTransactionEvents: PreparedUpdate => Future[PreparedUpdate] = {
    case offsetUpdate @ PreparedTransactionInsert(_, _, preparedInsert) =>
      Timed.future(
        metrics.daml.index.db.storeTransactionEvents,
        ledgerDao
          .storeTransactionEvents(preparedInsert)
          .map(_ => offsetUpdate),
      )
    case offsetUpdate => Future.successful(offsetUpdate)
  }

  private def executeUpdate(
      preparedUpdate: PreparedUpdate
  )(implicit loggingContext: LoggingContext): Future[PersistenceResponse] =
    preparedUpdate match {
      case OffsetUpdate.PreparedTransactionInsert(offsetStep, tx, _) =>
        Timed.future(
          metrics.daml.index.db.storeTransactionCompletion,
          ledgerDao.completeTransaction(
            submitterInfo = tx.optSubmitterInfo,
            transactionId = tx.transactionId,
            recordTime = tx.recordTime.toInstant,
            offsetStep = offsetStep,
          ),
        )
      case metadataUpdate: OffsetUpdate.MetadataUpdateStep => updateMetadata(metadataUpdate)
    }
}

object PipelinedUpdateIndexer {

  def owner(
      ledgerDao: LedgerDao,
      metrics: Metrics,
      participantId: v1.ParticipantId,
      executionContext: ExecutionContext,
      loggingContext: LoggingContext,
  ): ResourceOwner[ExecuteUpdateFlow] =
    ResourceOwner.successful(
      new PipelinedUpdateIndexer(ledgerDao, metrics, participantId)(
        executionContext,
        loggingContext,
      ).flow
    )
}

class AtomicUpdateIndexer(
    val ledgerDao: LedgerDao,
    val metrics: Metrics,
    val participantId: v1.ParticipantId,
)(implicit val loggingContext: LoggingContext, val executionContext: ExecutionContext)
    extends UpdateIndexer {

  override def flow: Flow[OffsetStepUpdatePair[Update], Unit, NotUsed] =
    Flow[OffsetStepUpdatePair[Update]]
      .mapAsync(1)(prepareUpdate)
      .mapAsync(1) { case offsetUpdate @ OffsetUpdate(offsetStep, update) =>
        withEnrichedLoggingContext(loggingContextFor(offsetStep.offset, update)) {
          implicit loggingContext =>
            Timed.future(
              metrics.daml.indexer.stateUpdateProcessing,
              executeUpdate(offsetUpdate),
            )
        }
      }
      .map(_ => ())

  private def executeUpdate(
      preparedUpdate: PreparedUpdate
  )(implicit loggingContext: LoggingContext): Future[PersistenceResponse] =
    preparedUpdate match {
      case PreparedTransactionInsert(
            offsetStep,
            TransactionAccepted(
              optSubmitterInfo,
              transactionMeta,
              transaction,
              transactionId,
              recordTime,
              divulgedContracts,
              blindingInfo,
            ),
            preparedInsert,
          ) =>
        Timed.future(
          metrics.daml.index.db.storeTransaction,
          ledgerDao.storeTransaction(
            preparedInsert,
            submitterInfo = optSubmitterInfo,
            transactionId = transactionId,
            recordTime = recordTime.toInstant,
            ledgerEffectiveTime = transactionMeta.ledgerEffectiveTime.toInstant,
            offsetStep = offsetStep,
            transaction = transaction,
            divulged = divulgedContracts,
            blindingInfo = blindingInfo,
          ),
        )

      case metadataUpdate: OffsetUpdate.MetadataUpdateStep => updateMetadata(metadataUpdate)
    }
}

object AtomicUpdateIndexer {
  def owner(ledgerDao: LedgerDao, metrics: Metrics, participantId: v1.ParticipantId)(implicit
      loggingContext: LoggingContext,
      executionContext: ExecutionContext,
  ): ResourceOwner[ExecuteUpdateFlow] =
    ResourceOwner.successful(new AtomicUpdateIndexer(ledgerDao, metrics, participantId).flow)
}
