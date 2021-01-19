package com.daml.platform.indexer

import java.time.Instant

import akka.stream.scaladsl.Source
import com.codahale.metrics.MetricRegistry
import com.daml.ledger.WorkflowId
import com.daml.ledger.api.testing.utils.AkkaBeforeAndAfterAll
import com.daml.ledger.participant.state.v1.Update.{
  PublicPackageUploadRejected,
  TransactionAccepted,
}
import com.daml.ledger.participant.state.v1._
import com.daml.ledger.resources.TestResourceContext
import com.daml.lf.data.{Bytes, ImmArray, Time}
import com.daml.lf.transaction.{BlindingInfo, NodeId, TransactionVersion, VersionedTransaction}
import com.daml.lf.value.Value.ContractId
import com.daml.lf.{crypto, transaction}
import com.daml.logging.LoggingContext
import com.daml.metrics.Metrics
import com.daml.platform.indexer.OffsetUpdate.{
  MetadataUpdateStep,
  OffsetStepUpdatePair,
  PreparedTransactionInsert,
}
import com.daml.platform.store.dao.{LedgerDao, PersistenceResponse}
import com.daml.platform.store.dao.events.TransactionsWriter
import com.daml.platform.store.entries.PackageLedgerEntry
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.OneInstancePerTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.{ExecutionContext, Future}

final class ExecuteUpdateSpec
    extends AsyncWordSpec
    with Matchers
    with MockitoSugar
    with ArgumentMatchersSugar
    with OneInstancePerTest
    with TestResourceContext
    with AkkaBeforeAndAfterAll {
  private val loggingContext = LoggingContext.ForTesting

  private val mockedPreparedInsert = mock[TransactionsWriter.PreparedInsert]
  private val offset = Offset(Bytes.assertFromString("01"))
  private val txId = TransactionId.fromInt(1)
  private val txMock = transaction.CommittedTransaction(
    VersionedTransaction[NodeId, ContractId](TransactionVersion.VDev, Map.empty, ImmArray.empty)
  )
  private val someMetrics = new Metrics(new MetricRegistry)
  private val someParticipantId = ParticipantId.assertFromString("some-participant")
  private val ledgerEffectiveTime = Instant.EPOCH

  private val packageUploadRejectionReason = "some rejection reason"
  private val submissionId = SubmissionId.assertFromString("s1")
  private val packageUploadRejectedEntry = PackageLedgerEntry.PackageUploadRejected(
    submissionId,
    ledgerEffectiveTime,
    packageUploadRejectionReason,
  )

  private val txAccepted = transactionAccepted(
    submitterInfo = None,
    workflowId = None,
    transactionId = txId,
    ledgerEffectiveTime = Instant.EPOCH,
    transaction = txMock,
    divulgedContracts = List.empty,
    blindingInfo = None,
  )

  private val currentOffset = CurrentOffset(offset = offset)
  private val offsetStepUpdatePair = OffsetStepUpdatePair(currentOffset, txAccepted)

  private val ledgerDaoMock = {

    /** Maybe inline some values from below (default ones) */
    val mocked = mock[LedgerDao]
    val submitterInfo = None
    val workflowId = None
    val divulgedContracts = List.empty[DivulgedContract]
    val blindingInfo = None

    when(
      mocked.prepareTransactionInsert(
        submitterInfo = submitterInfo,
        workflowId = workflowId,
        transactionId = txId,
        ledgerEffectiveTime = ledgerEffectiveTime,
        offset = offset,
        transaction = txMock,
        divulgedContracts = divulgedContracts,
        blindingInfo = blindingInfo,
      )
    ).thenReturn(mockedPreparedInsert)

    when(mocked.storeTransactionState(mockedPreparedInsert)(loggingContext))
      .thenReturn(Future.successful(PersistenceResponse.Ok))
    when(mocked.storeTransactionEvents(mockedPreparedInsert)(loggingContext))
      .thenReturn(Future.successful(PersistenceResponse.Ok))
    when(
      mocked.completeTransaction(
        eqTo(Option.empty[SubmitterInfo]),
        eqTo(txId),
        eqTo(ledgerEffectiveTime),
        eqTo(CurrentOffset(offset)),
      )(any[LoggingContext])
    )
      .thenReturn(Future.successful(PersistenceResponse.Ok))
    when(
      mocked.storePackageEntry(
        eqTo(currentOffset),
        eqTo(List.empty),
        eqTo(Some(packageUploadRejectedEntry)),
      )(any[LoggingContext])
    )
      .thenReturn(Future.successful(PersistenceResponse.Ok))
    mocked
  }

  trait ExecuteUpdateMock extends ExecuteUpdate {
    override implicit val loggingContext: LoggingContext = ExecuteUpdateSpec.this.loggingContext
    override implicit val executionContext: ExecutionContext =
      ExecuteUpdateSpec.this.materializer.executionContext

    override def participantId: ParticipantId = someParticipantId

    override def ledgerDao: LedgerDao = ledgerDaoMock

    override def metrics: Metrics = someMetrics
  }

  private val executeUpdate = new ExecuteUpdateMock {}

  "prepareUpdate" when {
    "receives a TransactionAccepted" should {
      "prepare a transaction insert" in {

        val eventualPreparedUpdate = executeUpdate.prepareUpdate(offsetStepUpdatePair)

        eventualPreparedUpdate.map {
          case OffsetUpdate.PreparedTransactionInsert(offsetStep, update, preparedInsert) =>
            offsetStep shouldBe currentOffset
            update shouldBe txAccepted
            preparedInsert shouldBe mockedPreparedInsert
          case _ => fail(s"Should be a ${classOf[PreparedTransactionInsert].getSimpleName}")
        }
      }
    }

    "receives a MetadataUpdate" should {
      "return a MetadataUpdateStep" in {
        val someMetadataUpdate = mock[MetadataUpdate]
        val offsetStepUpdatePair = OffsetStepUpdatePair(currentOffset, someMetadataUpdate)
        executeUpdate
          .prepareUpdate(offsetStepUpdatePair)
          .map(_ shouldBe MetadataUpdateStep(currentOffset, someMetadataUpdate))
      }
    }
  }

  classOf[PipelinedExecuteUpdate].getSimpleName when {
    "receives MetadataUpdateStep" should {
      "just execute metadata update" in {
        val packageUploadRejected = PublicPackageUploadRejected(
          submissionId = submissionId,
          recordTime = Time.Timestamp(ledgerEffectiveTime.toEpochMilli),
          rejectionReason = packageUploadRejectionReason,
        )
        val metadataUpdateOffsetPair = OffsetStepUpdatePair(currentOffset, packageUploadRejected)
        PipelinedExecuteUpdate
          .owner(ledgerDaoMock, someMetrics, someParticipantId, executionContext, loggingContext)
          .use { flow =>
            Source
              .single(metadataUpdateOffsetPair)
              .via(flow)
              .run()
              .map { _ =>
                verify(ledgerDaoMock).storePackageEntry(
                  eqTo(currentOffset),
                  eqTo(List.empty),
                  eqTo(Some(packageUploadRejectedEntry)),
                )(any[LoggingContext])
                verifyNoMoreInteractions(ledgerDaoMock)
                succeed
              }
          }
      }
    }

    "receives PreparedInsert" should {
      "process transaction pipelined" in {
        PipelinedExecuteUpdate
          .owner(ledgerDaoMock, someMetrics, someParticipantId, executionContext, loggingContext)
          .use { flow =>
            Source
              .single(offsetStepUpdatePair)
              .via(flow)
              .run()
              .map { _ =>
                val ordered = inOrder(ledgerDaoMock)
                ordered
                  .verify(ledgerDaoMock)
                  .storeTransactionState(eqTo(mockedPreparedInsert))(any[LoggingContext])
                ordered
                  .verify(ledgerDaoMock)
                  .storeTransactionEvents(eqTo(mockedPreparedInsert))(any[LoggingContext])
                ordered
                  .verify(ledgerDaoMock)
                  .completeTransaction(
                    eqTo(Option.empty[SubmitterInfo]),
                    eqTo(txId),
                    eqTo(ledgerEffectiveTime),
                    eqTo(CurrentOffset(offset)),
                  )(any[LoggingContext])

                succeed
              }
          }
      }
    }
  }

  private def transactionAccepted(
      submitterInfo: Option[SubmitterInfo],
      workflowId: Option[WorkflowId],
      transactionId: TransactionId,
      ledgerEffectiveTime: Instant,
      transaction: CommittedTransaction,
      divulgedContracts: List[DivulgedContract],
      blindingInfo: Option[BlindingInfo],
  ): TransactionAccepted = {
    val ledgerTimestamp = Time.Timestamp(ledgerEffectiveTime.toEpochMilli)
    TransactionAccepted(
      optSubmitterInfo = submitterInfo,
      transactionMeta = TransactionMeta(
        ledgerEffectiveTime = ledgerTimestamp,
        workflowId = workflowId,
        submissionTime = ledgerTimestamp,
        submissionSeed = crypto.Hash.hashPrivateKey("dummy"),
        optUsedPackages = None,
        optNodeSeeds = None,
        optByKeyNodes = None,
      ),
      transaction = transaction,
      transactionId = transactionId,
      recordTime = ledgerTimestamp,
      divulgedContracts = divulgedContracts,
      blindingInfo = blindingInfo,
    )
  }
}
