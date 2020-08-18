// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.speedy

import com.daml.lf.crypto
import com.daml.lf.scenario.ScenarioLedger
import com.daml.lf.data.Ref._
import com.daml.lf.data.{Ref, Time}
import com.daml.lf.engine.Engine
import com.daml.lf.language.Ast
import com.daml.lf.transaction.{GlobalKey, SubmittedTransaction, Transaction => Tx}
import com.daml.lf.value.Value.{ContractId, ContractInst}
import com.daml.lf.speedy.SError._
import com.daml.lf.speedy.SResult._

private case class SRunnerException(err: SError) extends RuntimeException(err.toString)

/** Speedy scenario runner that uses the reference ledger.
  *
  * @constructor Creates a runner using an instance of [[Speedy.Machine]].
  * @param partyNameMangler allows to amend party names defined in scenarios,
  *        before they are executed against a ledger. The function should be idempotent
  *        in the context of a single {@code ScenarioRunner} life-time, i.e. return the
  *        same result each time given the same argument. Should return values compatible
  *        with [[com.daml.lf.data.Ref.Party]].
  */
final case class ScenarioRunner(
    machine: Speedy.Machine,
    partyNameMangler: (String => String) = identity) {
  var ledger: ScenarioLedger = ScenarioLedger.initialLedger(Time.Timestamp.Epoch)

  def run(): Either[(SError, ScenarioLedger), (Double, Int, ScenarioLedger, SValue)] =
    ScenarioRunner.handleUnsafe(runUnsafe) match {
      case Left(err) => Left((err, ledger))
      case Right(t) => Right(t)
    }

  private def runUnsafe(): (Double, Int, ScenarioLedger, SValue) = {
    // NOTE(JM): Written with an imperative loop and exceptions for speed
    // and so that we don't need to worry about stack usage.
    val startTime = System.nanoTime()
    var steps = 0
    var finalValue: SValue = null
    while (finalValue == null) {
      //machine.print(steps)
      steps += 1 // this counts the number of external `Need` interactions
      val res: SResult = machine.run()
      res match {
        case SResultFinalValue(v) =>
          finalValue = v
        case SResultError(err) =>
          throw SRunnerException(err)

        case SResultNeedPackage(pkgId, _) =>
          crash(s"package $pkgId not found")

        case SResultNeedContract(coid, tid @ _, committers, cbMissing, cbPresent) =>
          ScenarioRunner.lookupContract(ledger, coid, committers, cbMissing, cbPresent)

        case SResultNeedTime(callback) =>
          callback(ledger.currentTime)

        case SResultScenarioMustFail(tx, committers, callback) =>
          mustFail(tx, committers)
          callback(())

        case SResultScenarioCommit(value, tx, committers, callback) =>
          commit(value, tx, committers, callback)

        case SResultScenarioPassTime(delta, callback) =>
          passTime(delta, callback)

        case SResultScenarioInsertMustFail(committers, optLocation) => {
          val committer =
            if (committers.size == 1) committers.head
            else ScenarioRunner.crashTooManyCommitters(committers)

          ledger = ledger.insertAssertMustFail(committer, optLocation)
        }

        case SResultScenarioGetParty(partyText, callback) =>
          getParty(partyText, callback)

        case SResultNeedKey(keyWithMaintainers, committers, cb) =>
          ScenarioRunner.lookupKeyUnsafe(ledger, keyWithMaintainers.globalKey, committers, cb)
      }
    }
    val endTime = System.nanoTime()
    val diff = (endTime - startTime) / 1000.0 / 1000.0
    (diff, steps, ledger, finalValue)
  }

  private def getParty(partyText: String, callback: Party => Unit) = {
    val mangledPartyText = partyNameMangler(partyText)
    Party.fromString(mangledPartyText) match {
      case Right(s) => callback(s)
      case Left(msg) => throw SRunnerException(ScenarioErrorInvalidPartyName(partyText, msg))
    }
  }

  private def mustFail(tx: SubmittedTransaction, committers: Set[Party]) = {
    // Update expression evaluated successfully,
    // however we might still have an authorization failure.
    val committer =
      if (committers.size == 1) committers.head
      else ScenarioRunner.crashTooManyCommitters(committers)

    if (ScenarioLedger
        .commitTransaction(
          committer = committer,
          effectiveAt = ledger.currentTime,
          optLocation = machine.commitLocation,
          tx = tx,
          l = ledger)
        .isRight) {
      throw SRunnerException(ScenarioErrorMustFailSucceeded(tx))
    }
    ledger = ledger.insertAssertMustFail(committer, machine.commitLocation)
  }

  private def commit(
      value: SValue,
      tx: SubmittedTransaction,
      committers: Set[Party],
      callback: SValue => Unit) = {
    val committer =
      if (committers.size == 1) committers.head
      else ScenarioRunner.crashTooManyCommitters(committers)

    ScenarioLedger.commitTransaction(
      committer = committer,
      effectiveAt = ledger.currentTime,
      optLocation = machine.commitLocation,
      tx = tx,
      l = ledger
    ) match {
      case Left(fas) =>
        throw SRunnerException(ScenarioErrorCommitError(fas))
      case Right(result) =>
        ledger = result.newLedger
        callback(value)
    }
  }

  private def passTime(delta: Long, callback: Time.Timestamp => Unit) = {
    ledger = ledger.passTime(delta)
    callback(ledger.currentTime)
  }

}

object ScenarioRunner {

  import scala.util.{Try, Success, Failure}

  @deprecated("can be used only by sandbox classic.", since = "1.4.0")
  def getScenarioLedger(
      engine: Engine,
      scenarioRef: Ref.DefinitionRef,
      scenarioDef: Ast.Definition,
      transactionSeed: crypto.Hash,
  ): ScenarioLedger = {
    val scenarioExpr = getScenarioExpr(scenarioRef, scenarioDef)
    val speedyMachine = Speedy.Machine.fromScenarioExpr(
      engine.compiledPackages(),
      transactionSeed,
      scenarioExpr,
      engine.config.allowedInputValueVersions,
      engine.config.allowedOutputTransactionVersions,
    )
    ScenarioRunner(speedyMachine).run() match {
      case Left(e) =>
        throw new RuntimeException(s"error running scenario $scenarioRef in scenario $e")
      case Right((_, _, l, _)) => l
    }
  }

  private[this] def getScenarioExpr(
      scenarioRef: Ref.DefinitionRef,
      scenarioDef: Ast.Definition): Ast.Expr = {
    scenarioDef match {
      case Ast.DValue(_, _, body, _) => body
      case _: Ast.DTypeSyn =>
        throw new RuntimeException(
          s"Requested scenario $scenarioRef is a type synonym, not a definition")
      case _: Ast.DDataType =>
        throw new RuntimeException(
          s"Requested scenario $scenarioRef is a data type, not a definition")
    }
  }

  private[speedy] def handleUnsafe[T](unsafe: => T): Either[SError, T] = {
    Try(unsafe) match {
      case Failure(SRunnerException(err)) => Left(err)
      case Failure(other) => throw other
      case Success(t) => Right(t)
    }
  }

  private[speedy] def crash(reason: String) =
    throw SRunnerException(SErrorCrash(reason))

  private def crashTooManyCommitters(committers: Set[Party]) =
    crash(s"Expecting one committer for scenario action, but got $committers")

  private[lf] def lookupContract(
      ledger: ScenarioLedger,
      acoid: ContractId,
      committers: Set[Party],
      cbMissing: Unit => Boolean,
      cbPresent: ContractInst[Tx.Value[ContractId]] => Unit): Either[SError, Unit] =
    handleUnsafe(lookupContractUnsafe(ledger, acoid, committers, cbMissing, cbPresent))

  private[speedy] def lookupContractUnsafe(
      ledger: ScenarioLedger,
      acoid: ContractId,
      committers: Set[Party],
      cbMissing: Unit => Boolean,
      cbPresent: ContractInst[Tx.Value[ContractId]] => Unit) = {

    val committer =
      if (committers.size == 1) committers.head else crashTooManyCommitters(committers)
    val effectiveAt = ledger.currentTime

    def missingWith(err: SError) =
      if (!cbMissing(()))
        throw SRunnerException(err)

    ledger.lookupGlobalContract(
      view = ScenarioLedger.ParticipantView(committer),
      effectiveAt = effectiveAt,
      acoid) match {
      case ScenarioLedger.LookupOk(_, coinst, _) =>
        cbPresent(coinst)

      case ScenarioLedger.LookupContractNotFound(coid) =>
        // This should never happen, hence we don't have a specific
        // error for this.
        missingWith(SErrorCrash(s"contract $coid not found"))

      case ScenarioLedger.LookupContractNotEffective(coid, tid, effectiveAt) =>
        missingWith(ScenarioErrorContractNotEffective(coid, tid, effectiveAt))

      case ScenarioLedger.LookupContractNotActive(coid, tid, consumedBy) =>
        missingWith(ScenarioErrorContractNotActive(coid, tid, consumedBy))

      case ScenarioLedger.LookupContractNotVisible(coid, tid, observers, stakeholders @ _) =>
        missingWith(ScenarioErrorContractNotVisible(coid, tid, committer, observers))
    }
  }

  private[lf] def lookupKey(
      ledger: ScenarioLedger,
      gk: GlobalKey,
      committers: Set[Party],
      canContinue: SKeyLookupResult => Boolean,
  ): Either[SError, Unit] =
    handleUnsafe(lookupKeyUnsafe(ledger, gk, committers, canContinue))

  private[speedy] def lookupKeyUnsafe(
      ledger: ScenarioLedger,
      gk: GlobalKey,
      committers: Set[Party],
      canContinue: SKeyLookupResult => Boolean,
  ): Unit = {
    val committer =
      if (committers.size == 1) committers.head else crashTooManyCommitters(committers)
    val effectiveAt = ledger.currentTime

    def missingWith(err: SError) =
      if (!canContinue(SKeyLookupResult.NotFound))
        throw SRunnerException(err)

    def notVisibleWith(err: SError) =
      if (!canContinue(SKeyLookupResult.NotVisible))
        throw SRunnerException(err)

    ledger.ledgerData.activeKeys.get(gk) match {
      case None =>
        missingWith(SErrorCrash(s"Key $gk not found"))
      case Some(acoid) =>
        ledger.lookupGlobalContract(
          view = ScenarioLedger.ParticipantView(committer),
          effectiveAt = effectiveAt,
          acoid) match {
          case ScenarioLedger.LookupOk(_, _, stakeholders) =>
            if (stakeholders.contains(committer))
              // We should always be able to continue with a SKeyLookupResult.Found.
              // Run to get side effects and assert result.
              assert(canContinue(SKeyLookupResult.Found(acoid)))
            else
              notVisibleWith(ScenarioErrorContractKeyNotVisible(acoid, gk, committer, stakeholders))
          case ScenarioLedger.LookupContractNotFound(coid) =>
            missingWith(SErrorCrash(s"contract $coid not found, but we found its key!"))
          case ScenarioLedger.LookupContractNotEffective(_, _, _) =>
            missingWith(SErrorCrash(s"contract $acoid not effective, but we found its key!"))
          case ScenarioLedger.LookupContractNotActive(_, _, _) =>
            missingWith(SErrorCrash(s"contract $acoid not active, but we found its key!"))
          case ScenarioLedger.LookupContractNotVisible(
              coid,
              tid @ _,
              observers @ _,
              stakeholders,
              ) =>
            notVisibleWith(ScenarioErrorContractKeyNotVisible(coid, gk, committer, stakeholders))
        }
    }
  }
}
