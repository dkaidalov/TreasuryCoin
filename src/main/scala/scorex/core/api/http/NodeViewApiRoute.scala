package scorex.core.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import io.circe.syntax._
import scorex.core.NodeViewHolder.CurrentView
import scorex.core.consensus.History
import scorex.core.network.ConnectedPeer
import scorex.core.settings.RESTApiSettings
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.state.MinimalState
import scorex.core.transaction.wallet.Vault
import scorex.core.transaction.{MemoryPool, Transaction}
import scorex.core.{ModifierId, PersistentNodeViewModifier}
import scorex.crypto.encode.Base58

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}


case class NodeViewApiRoute[P <: Proposition, TX <: Transaction[P]]
(override val settings: RESTApiSettings, nodeViewHolderRef: ActorRef)
(implicit val context: ActorRefFactory) extends ApiRoute {

  import scorex.core.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView

  override val route = (pathPrefix("nodeView") & withCors) {
    openSurface ~ persistentModifierById ~ pool
  }

  type PM <: PersistentNodeViewModifier
  type HIS <: History[PM, _, _ <: History[PM, _, _]]
  type MP <: MemoryPool[TX, _ <: MemoryPool[TX, _]]
  type MS <: MinimalState[PM, _ <: MinimalState[_, _]]
  type VL <: Vault[P, TX, PM, _ <: Vault[P, TX, PM, _]]

  //TODO null?
  private val source: ConnectedPeer = null

  case class OpenSurface(ids: Seq[ModifierId])

  def withOpenSurface(fn: OpenSurface => Route): Route = {
    def f(v: CurrentView[HIS, MS, VL, MP]): OpenSurface = OpenSurface(v.history.openSurfaceIds())
    val futureOpenSurface = (nodeViewHolderRef ? GetDataFromCurrentView(f)).map(_.asInstanceOf[OpenSurface])
    onSuccess(futureOpenSurface)(fn)
  }

  case class MempoolData(size: Int, transactions: Iterable[TX])

  def withMempool(fn: MempoolData => Route): Route = {
    def f(v: CurrentView[HIS, MS, VL, MP]): MempoolData = MempoolData(v.pool.size, v.pool.take(1000))
    val futureMempoolData = (nodeViewHolderRef ? GetDataFromCurrentView(f)).map(_.asInstanceOf[MempoolData])
    onSuccess(futureMempoolData)(fn)
  }

  def withPersistentModifier(encodedId: String)(fn: PM => Route): Route = {
    Base58.decode(encodedId) match {
      case Failure(e) => complete(ApiError.notExists)
      case Success(rawId) =>
        val id = ModifierId @@ rawId

        def f(v: CurrentView[HIS, MS, VL, MP]): Option[PM] = v.history.modifierById(id)

        val futurePersistentModifier = (nodeViewHolderRef ? GetDataFromCurrentView[HIS, MS, VL, MP, Option[PM]](f)).mapTo[Option[PM]]
        onComplete(futurePersistentModifier) {
          case Success(Some(tx)) => fn(tx)
          case Success(None) => complete(ApiError.notExists)
          case Failure(_) => complete(ApiError.notExists)
        }
    }
  }

  def pool: Route = (get & path("pool")) {
    withMempool { mpd =>
      complete(SuccessApiResponse(
          "size" -> mpd.size.asJson,
          "transactions" -> mpd.transactions.map(_.json).asJson
      ))
    }
  }

  def openSurface: Route = (get & path("openSurface")) {
    withOpenSurface { os =>
      complete(SuccessApiResponse(os.ids.map(Base58.encode).asJson))
    }
  }

  def persistentModifierById: Route = (get & path("persistentModifier" / Segment)) { encodedId =>
    withPersistentModifier(encodedId) { tx =>
      complete(SuccessApiResponse(tx.json))
    }
  }

}
