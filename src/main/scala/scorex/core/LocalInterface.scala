package scorex.core

import akka.actor.{Actor, ActorRef}
import scorex.core.NodeViewLocalInterfaceSharedMessages.ReceivableMessages.{ChangedHistory, ChangedMempool, ChangedVault}
import scorex.core.consensus.{HistoryReader, SyncInfo}
import scorex.core.transaction.{MempoolReader, Transaction}
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.state.StateReader
import scorex.core.utils.ScorexLogging

/**
  *
  */
trait LocalInterface[P <: Proposition, TX <: Transaction[P], PMOD <: PersistentNodeViewModifier]
  extends Actor with ScorexLogging {

  import scorex.core.LocalInterface.ReceivableMessages._
  import scorex.core.NodeViewLocalInterfaceSharedMessages.ReceivableMessages.{SuccessfulTransaction, FailedTransaction,
                                                                              SyntacticallySuccessfulModifier, SyntacticallyFailedModification,
                                                                              SemanticallySuccessfulModifier, SemanticallyFailedModification,
                                                                              ChangedState, NewOpenSurface, RollbackFailed,
                                                                              StartingPersistentModifierApplication}
  import scorex.core.NodeViewHolder.ReceivableMessages.Subscribe
  import scorex.core.LocallyGeneratedModifiersMessages.ReceivableMessages.{LocallyGeneratedTransaction, LocallyGeneratedModifier}

  val viewHolderRef: ActorRef

  override def preStart(): Unit = {
    val events = Seq(
      NodeViewHolder.EventType.SuccessfulTransaction,
      NodeViewHolder.EventType.FailedTransaction,

      NodeViewHolder.EventType.StartingPersistentModifierApplication,
      NodeViewHolder.EventType.SyntacticallyFailedPersistentModifier,
      NodeViewHolder.EventType.SemanticallyFailedPersistentModifier,
      NodeViewHolder.EventType.SuccessfulSyntacticallyValidModifier,
      NodeViewHolder.EventType.SuccessfulSemanticallyValidModifier,

      NodeViewHolder.EventType.OpenSurfaceChanged,
      NodeViewHolder.EventType.StateChanged,
      NodeViewHolder.EventType.FailedRollback,

      NodeViewHolder.EventType.StateChanged,
      NodeViewHolder.EventType.HistoryChanged,
      NodeViewHolder.EventType.MempoolChanged,
      NodeViewHolder.EventType.VaultChanged
    )
    viewHolderRef ! Subscribe(events)
  }

  private def viewHolderEvents: Receive = {
    case st: SuccessfulTransaction[P, TX] =>
      onSuccessfulTransaction(st.transaction)

    case ft: FailedTransaction[P, TX] =>
      onFailedTransaction(ft.transaction)

    case stm: StartingPersistentModifierApplication[PMOD] =>
      onStartingPersistentModifierApplication(stm.modifier)

    case syns: SyntacticallySuccessfulModifier[PMOD] =>
      onSyntacticallySuccessfulModification(syns.modifier)

    case synf: SyntacticallyFailedModification[PMOD] =>
      onSyntacticallyFailedModification(synf.modifier)

    case sems: SemanticallySuccessfulModifier[PMOD] =>
      onSemanticallySuccessfulModification(sems.modifier)

    case semf: SemanticallyFailedModification[PMOD] =>
      onSemanticallyFailedModification(semf.modifier)

    case surf: NewOpenSurface =>
      onNewSurface(surf.newSurface)

    case RollbackFailed =>
      onRollbackFailed()

    case ChangedState(r) =>
      onChangedState(r)

    case ChangedHistory(r) =>
      onChangedHistory(r)

    case ChangedMempool(r) =>
      onChangedMempool(r)

    case ChangedVault() =>
      onChangedVault()
  }


  protected def onSuccessfulTransaction(tx: TX): Unit
  protected def onFailedTransaction(tx: TX): Unit


  protected def onStartingPersistentModifierApplication(pmod: PMOD): Unit

  protected def onSyntacticallySuccessfulModification(mod: PMOD): Unit
  protected def onSyntacticallyFailedModification(mod: PMOD): Unit

  protected def onSemanticallySuccessfulModification(mod: PMOD): Unit
  protected def onSemanticallyFailedModification(mod: PMOD): Unit

  protected def onNewSurface(newSurface: Seq[ModifierId]): Unit
  protected def onRollbackFailed(): Unit
  protected def onChangedState(r: StateReader): Unit = {}
  protected def onChangedHistory(r: HistoryReader[_ <: PersistentNodeViewModifier, _ <: SyncInfo]): Unit
  protected def onChangedMempool(r: MempoolReader[_ <: Transaction[_]]): Unit
  protected def onChangedVault(): Unit

  protected def onNoBetterNeighbour(): Unit
  protected def onBetterNeighbourAppeared(): Unit

  override def receive: Receive = viewHolderEvents orElse {
    case NoBetterNeighbour =>
      onNoBetterNeighbour()
    case BetterNeighbourAppeared =>
      onBetterNeighbourAppeared()
    case lt: LocallyGeneratedTransaction[P, TX] =>
      viewHolderRef ! lt
    case lm: LocallyGeneratedModifier[PMOD] =>
      viewHolderRef ! lm
    case a: Any => log.error("Strange input: " + a)
  }
}

object LocalInterface {
  object ReceivableMessages {
    case object NoBetterNeighbour
    case object BetterNeighbourAppeared
  }
}