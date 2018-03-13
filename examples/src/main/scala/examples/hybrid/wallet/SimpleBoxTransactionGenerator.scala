package examples.hybrid.wallet

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import examples.commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool, SimpleBoxTx, Value}
import examples.commons.Value
import examples.hybrid.history.HybridHistory
import examples.hybrid.state.HBoxStoredState
import scorex.core.NodeViewHolder.CurrentView
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Random, Success, Try}

/**
  * Generator of SimpleBoxTransaction inside a wallet
  */
class SimpleBoxTransactionGenerator(viewHolderRef: ActorRef) extends Actor with ScorexLogging {

  import SimpleBoxTransactionGenerator.ReceivableMessages._
  import scorex.core.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
  import scorex.core.LocallyGeneratedModifiersMessages.ReceivableMessages.LocallyGeneratedTransaction

  private val getRequiredData: GetDataFromCurrentView[HybridHistory,
    HBoxStoredState,
    HWallet,
    SimpleBoxTransactionMemPool,
    GeneratorInfo] = {
    val f: CurrentView[HybridHistory, HBoxStoredState, HWallet, SimpleBoxTransactionMemPool] => GeneratorInfo = {
      view: CurrentView[HybridHistory, HBoxStoredState, HWallet, SimpleBoxTransactionMemPool] =>
        GeneratorInfo(generate(view.vault))
    }
    GetDataFromCurrentView[HybridHistory,
      HBoxStoredState,
      HWallet,
      SimpleBoxTransactionMemPool,
      GeneratorInfo](f)
  }


  override def receive: Receive = {
    case StartGeneration(duration) =>
      context.system.scheduler.schedule(duration, duration, viewHolderRef, getRequiredData)

    //    case CurrentView(_, _, wallet: HWallet, _) =>
    case gi: GeneratorInfo =>
      gi.tx match {
        case Success(tx) =>
          log.info(s"Local tx with with ${tx.from.size} inputs, ${tx.to.size} outputs. Valid: ${tx.semanticValidity}")
          viewHolderRef ! LocallyGeneratedTransaction[PublicKey25519Proposition, SimpleBoxTransaction](tx)
        case Failure(e) =>
          e.printStackTrace()
      }
  }

  private val ex: ArrayBuffer[Array[Byte]] = ArrayBuffer()

  def generate(wallet: HWallet): Try[SimpleBoxTransaction] = {
    if (Random.nextInt(100) == 1) ex.clear()

    val pubkeys = wallet.publicKeys.toSeq
    if (pubkeys.size < 10) wallet.generateNewSecret()
    val recipients = scala.util.Random.shuffle(pubkeys).take(Random.nextInt(pubkeys.size))
      .map(r => (r, Value @@ Random.nextInt(100).toLong))
    val tx = SimpleBoxTx.create(wallet, recipients, Random.nextInt(100), ex)
    tx.map(t => t.boxIdsToOpen.foreach(id => ex += id))
    tx
  }
}

object SimpleBoxTransactionGenerator {
  object ReceivableMessages {
    case class StartGeneration(delay: FiniteDuration)
    case class GeneratorInfo(tx: Try[SimpleBoxTransaction])
  }
}

object SimpleBoxTransactionGeneratorRef {
  def props(viewHolderRef: ActorRef): Props = Props(new SimpleBoxTransactionGenerator(viewHolderRef))
  def apply(viewHolderRef: ActorRef)
           (implicit system: ActorSystem): ActorRef = system.actorOf(props(viewHolderRef))
  def apply(name: String, viewHolderRef: ActorRef)
           (implicit system: ActorSystem): ActorRef = system.actorOf(props(viewHolderRef), name)
}
