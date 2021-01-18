package com.daml.platform.indexer

import com.daml.ledger.participant.state.v1.Update.TransactionAccepted
import com.daml.platform.store.dao.events.TransactionsWriter

class PrepareTransactionInsert private () extends PrepareTransactionInsert.Type {
  override def apply(
      transactionsWriter: TransactionsWriter,
      tx: TransactionAccepted,
      offsetStep: OffsetStep,
  ): TransactionsWriter.PreparedInsert =
    transactionsWriter.prepare(
      submitterInfo = tx.optSubmitterInfo,
      workflowId = tx.transactionMeta.workflowId,
      transactionId = tx.transactionId,
      ledgerEffectiveTime = tx.transactionMeta.ledgerEffectiveTime.toInstant,
      offset = offsetStep.offset,
      transaction = tx.transaction,
      divulgedContracts = tx.divulgedContracts,
      blindingInfo = tx.blindingInfo,
    )
}

object PrepareTransactionInsert {
  type Type =
    (TransactionsWriter, TransactionAccepted, OffsetStep) => TransactionsWriter.PreparedInsert
  def apply(): Type = new PrepareTransactionInsert
}
