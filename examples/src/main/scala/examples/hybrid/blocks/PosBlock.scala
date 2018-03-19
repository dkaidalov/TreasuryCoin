package examples.hybrid.blocks

import com.google.common.primitives.{Bytes, Ints, Longs}
import examples.commons.{PublicKey25519NoncedBox, PublicKey25519NoncedBoxSerializer, SimpleBoxTransaction, SimpleBoxTransactionCompanion}
import io.circe.{Encoder, Json}
import io.circe.syntax._
import scorex.core.{ModifierId, ModifierTypeId, TransactionsCarryingPersistentNodeViewModifier}
import scorex.core.block.Block
import scorex.core.block.Block._
import scorex.core.serialization.Serializer
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.proof.Signature25519
import scorex.core.transaction.state.PrivateKey25519
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.{Curve25519, Signature}

import scala.util.Try

case class PosBlock(override val parentId: BlockId, //PoW block
                    override val timestamp: Block.Timestamp,
                    override val transactions: Seq[SimpleBoxTransaction],
                    generatorBox: PublicKey25519NoncedBox,
                    attachment: Array[Byte],
                    signature: Signature25519
                   ) extends HybridBlock
  with TransactionsCarryingPersistentNodeViewModifier[PublicKey25519Proposition, SimpleBoxTransaction] {
  override type M = PosBlock

  override lazy val serializer = PosBlockCompanion

  override lazy val version: Version = 0: Byte

  override lazy val modifierTypeId: ModifierTypeId = PosBlock.ModifierTypeId

  override lazy val id: ModifierId =
    ModifierId @@ Blake2b256(parentId ++ Longs.toByteArray(timestamp) ++ generatorBox.id ++ attachment)

  override def toString: String = s"PoSBlock(${this.asJson.noSpaces})"
}

object PosBlockCompanion extends Serializer[PosBlock] {
  override def toBytes(b: PosBlock): Array[Byte] = {
    val txsBytes = b.transactions.foldLeft(Array[Byte]()) { (a, b) =>
      Bytes.concat(a, Ints.toByteArray(b.bytes.length), b.bytes)
    }
    Bytes.concat(b.parentId, Longs.toByteArray(b.timestamp), b.generatorBox.bytes, b.signature.bytes,
      Ints.toByteArray(b.transactions.length), txsBytes,
      Ints.toByteArray(b.attachment.length), b.attachment)
  }

  override def parseBytes(bytes: Array[Byte]): Try[PosBlock] = Try {
    // TODO: reconsider block size requerement taking into account treasury transactions
    //require(bytes.length <= PosBlock.MaxBlockSize)

    val parentId = ModifierId @@ bytes.slice(0, BlockIdLength)
    var position = BlockIdLength
    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position = position + 8

    val boxBytes = bytes.slice(position, position + PublicKey25519NoncedBox.BoxLength)
    val box = PublicKey25519NoncedBoxSerializer.parseBytes(boxBytes).get
    position = position + PublicKey25519NoncedBox.BoxLength

    val signature = Signature25519(Signature @@ bytes.slice(position, position + Signature25519.SignatureSize))
    position = position + Signature25519.SignatureSize

    val txsLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position = position + 4
    val txs: Seq[SimpleBoxTransaction] = (0 until txsLength) map { _ =>
      val l = Ints.fromByteArray(bytes.slice(position, position + 4))
      val txBytes = bytes.slice(position + 4, position + 4 + l)
      val tx: SimpleBoxTransaction = SimpleBoxTransactionCompanion.parseBytes(txBytes).get
      position = position + 4 + l
      tx
    }
    val attachmentLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    val attachment = bytes.slice(position + 4, position + 4 + attachmentLength)
    PosBlock(parentId, timestamp, txs, box, attachment, signature)
  }
}

object PosBlock {
  val MaxBlockSize = 512 * 1024  //512K
  val ModifierTypeId: ModifierTypeId = scorex.core.ModifierTypeId @@ 4.toByte

  implicit val posBlockEncoder: Encoder[PosBlock] = (psb: PosBlock) => {
    Map(
      "id" -> Base58.encode(psb.id).asJson,
      "parentId" -> Base58.encode(psb.parentId).asJson,
      "attachment" -> Base58.encode(psb.attachment).asJson,
      "timestamp" -> psb.timestamp.asJson,
      "transactions" -> psb.transactions.map(_.asJson).asJson,
      "generatorBox" -> psb.generatorBox.asJson,
      "signature" -> Base58.encode(psb.signature.bytes).asJson
    ).asJson
  }

  def create(parentId: BlockId,
             timestamp: Block.Timestamp,
             txs: Seq[SimpleBoxTransaction],
             box: PublicKey25519NoncedBox,
             attachment: Array[Byte],
             privateKey: PrivateKey25519): PosBlock = {
    require(box.proposition.pubKeyBytes sameElements privateKey.publicKeyBytes)
    val unsigned = PosBlock(parentId, timestamp, txs, box, attachment, Signature25519(Signature @@ Array[Byte]()))
    val signature = Curve25519.sign(privateKey.privKeyBytes, unsigned.bytes)
    unsigned.copy(signature = Signature25519(signature))
  }

  def signatureValid(posBlock: PosBlock): Boolean = {
    val unsignedBytes = posBlock.copy(signature = Signature25519(Signature @@ Array[Byte]())).bytes
    posBlock.generatorBox.proposition.verify(unsignedBytes, posBlock.signature.signature)
  }
}
