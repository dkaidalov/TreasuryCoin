package examples.hybrid.wallet

import java.io.File

import com.google.common.primitives.{Bytes, Ints, Longs}
import examples.commons._
import examples.hybrid.TreasuryManager
import examples.hybrid.TreasuryManager.Role.Role
import examples.hybrid.blocks.HybridBlock
import examples.hybrid.state.HBoxStoredState
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.VersionTag
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion, PrivateKey25519Serializer}
import scorex.core.transaction.wallet.{Wallet, WalletBox, WalletBoxSerializer, WalletTransaction}
import scorex.core.utils.{ByteStr, ScorexLogging}
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Blake2b256
import treasury.crypto.core.PubKey

import scala.util.Try


case class HWallet(seed: ByteStr, store: LSMStore, treasuryStore: LSMStore)
  extends Wallet[PublicKey25519Proposition, SimpleBoxTransaction, HybridBlock, HWallet]
    with ScorexLogging {

  override type S = PrivateKey25519
  override type PI = PublicKey25519Proposition

  private val BoxIdsKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(store.keySize)(1: Byte))
  private val SecretsKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(store.keySize)(2: Byte))

  def boxIds: Seq[Array[Byte]] = {
    store.get(BoxIdsKey).map(_.data.grouped(store.keySize).toSeq).getOrElse(Seq[Array[Byte]]())
  }

  private lazy val walletBoxSerializer =
    new WalletBoxSerializer[PublicKey25519Proposition, PublicKey25519NoncedBox](PublicKey25519NoncedBoxSerializer)

  //intentionally not implemented for now
  override def historyTransactions: Seq[WalletTransaction[PublicKey25519Proposition, SimpleBoxTransaction]] = ???

  override def boxes(): Seq[WalletBox[PublicKey25519Proposition, PublicKey25519NoncedBox]] = {
    boxIds
      .flatMap(id => store.get(ByteArrayWrapper(id)))
      .map(_.data)
      .map(ba => walletBoxSerializer.parseBytes(ba))
      .map(_.get)
      .filter(_.box.value > 0)
  }

  override def publicKeys: Set[PublicKey25519Proposition] = secrets.map(_.publicImage)

  override def secrets: Set[PrivateKey25519] = {
    store.get(SecretsKey)
      .map(_.data.grouped(64).map(b => PrivateKey25519Serializer.parseBytes(b).get).toSet)
      .getOrElse(Set.empty[PrivateKey25519])
  }

  override def secretByPublicImage(publicImage: PublicKey25519Proposition): Option[PrivateKey25519] =
    secrets.find(s => s.publicImage == publicImage)

  override def generateNewSecret(): HWallet = {
    val prevSecrets = secrets
    val nonce: Array[Byte] = Ints.toByteArray(prevSecrets.size)
    val s = Blake2b256(seed.arr ++ nonce)
    val (priv, _) = PrivateKey25519Companion.generateKeys(s)
    val allSecrets: Set[PrivateKey25519] = Set(priv) ++ prevSecrets
    store.update(ByteArrayWrapper(priv.privKeyBytes),
      Seq(),
      Seq(SecretsKey -> ByteArrayWrapper(allSecrets.toArray.flatMap(p => PrivateKey25519Serializer.toBytes(p)))))
    HWallet(seed, store, treasuryStore)
  }

  def prepareOutputs(to: Seq[(PublicKey25519Proposition, Value)],
                     fee: Long, boxesIdsToExclude: Seq[Array[Byte]] = Seq()):
    Try[(IndexedSeq[(PrivateKey25519, Nonce)], IndexedSeq[(PublicKey25519Proposition, Value)])] = Try {

    var s = 0L
    val amount = to.map(_._2.toLong).sum

    val from: IndexedSeq[(PrivateKey25519, Nonce, Value)] = boxes()
      .filter(b => !boxesIdsToExclude.exists(_ sameElements b.box.id)).sortBy(_.createdAt).takeWhile { b =>
      s = s + b.box.value
      s < amount + fee + b.box.value
    }.flatMap { b =>
      secretByPublicImage(b.box.proposition).map(s => (s, b.box.nonce, b.box.value))
    }.toIndexedSeq
    val canSend = from.map(_._3.toLong).sum
    require(canSend >= (amount + fee))

    val charge: Seq[(PublicKey25519Proposition, Value)] =
      if (canSend > amount + fee)
        Seq((publicKeys.headOption.get, Value @@ (canSend - amount - fee)))
      else Seq()

    val inputs = from.map(t => t._1 -> t._2)
    val outputs: IndexedSeq[(PublicKey25519Proposition, Value)] = (to ++ charge).toIndexedSeq

    require(from.map(_._3.toLong).sum - outputs.map(_._2.toLong).sum == fee)

    (inputs, outputs)
  }

  /**
    * @return sequence of treasury secrets by epochs
    */
  def treasurySecrets: Seq[(Long, Set[TreasurySecret])] = treasuryStore.getAll().map { b =>
    val epochID = Longs.fromByteArray(b._1.data)
    val trsecrets = TreasurySecretSerializer.parseBatch(b._2.data).toSet
    (epochID, trsecrets)
  }.toSeq

  def treasurySecrets(epochId: Long): Set[TreasurySecret] =
    treasuryStore.get(ByteArrayWrapper(Longs.toByteArray(epochId)))
      .map(s => TreasurySecretSerializer.parseBatch(s.data).toSet)
      .getOrElse(Set.empty[TreasurySecret])

  def treasurySigningSecrets(epochId: Long): Set[TreasurySigningSecret] =
    treasurySecrets(epochId).collect {case s: TreasurySigningSecret => s}

  def treasuryCommitteeSecrets(epochId: Long): Set[TreasuryCommitteeSecret] =
    treasurySecrets(epochId).collect {case s: TreasuryCommitteeSecret => s}

  def treasurySigningSecrets(role: Role, epochId: Long): Set[TreasurySigningSecret] =
    treasurySigningSecrets(epochId).filter(_.role == role)

  def treasurySigningPubKeys(role: Role, epochId: Long): Set[PublicKey25519Proposition] =
    treasurySigningSecrets(role, epochId).map(_.privKey.publicImage)

  def treasurySigningSecretByPubKey(epochId: Long, pubKey: PublicKey25519Proposition): Option[TreasurySigningSecret] =
    treasurySigningSecrets(epochId).find(s => s.privKey.publicImage.equals(pubKey))

  def generateNewTreasurySigningSecret(role: Role, epochId: Long): PublicKey25519Proposition = {
    val prevSigningSecrets = treasurySigningSecrets(epochId)
    val nonce: Array[Byte] = Bytes.concat(Longs.toByteArray(epochId), Ints.toByteArray(prevSigningSecrets.size))
    val s = Blake2b256(seed.arr ++ nonce)
    val (priv, _) = PrivateKey25519Companion.generateKeys(s)
    val newSecret = TreasurySigningSecret(role, priv, epochId)
    val allSecrets: Set[TreasurySecret] = Set(newSecret) ++ treasurySecrets(epochId)

    treasuryStore.update(ByteArrayWrapper(priv.bytes),
      Seq(),
      Seq(ByteArrayWrapper(Longs.toByteArray(epochId)) -> ByteArrayWrapper(TreasurySecretSerializer.batchToBytes(allSecrets.toSeq))))

    newSecret.privKey.publicImage
  }

  def generateNewTreasuryCommitteeSecret(epochId: Long): PubKey = {
    val prevTrSecrets = treasurySecrets(epochId)
    val (priv, pub) = TreasuryManager.cs.createKeyPair // TODO: keys should be generated from on a particular seed that includes role,epochId,nonce
    val secretKey = TreasuryManager.cs.getRand
    val newTrSecret = TreasuryCommitteeSecret(priv, pub, secretKey, epochId)
    val allTrSecrets: Set[TreasurySecret] = Set(newTrSecret) ++ prevTrSecrets
    treasuryStore.update(ByteArrayWrapper(priv.toByteArray),
      Seq(),
      Seq(ByteArrayWrapper(Longs.toByteArray(epochId)) -> ByteArrayWrapper(TreasurySecretSerializer.batchToBytes(allTrSecrets.toSeq))))

    newTrSecret.pubKey
  }

  //we do not process offchain (e.g. by adding them to the wallet)
  override def scanOffchain(tx: SimpleBoxTransaction): HWallet = this

  override def scanOffchain(txs: Seq[SimpleBoxTransaction]): HWallet = this

  override def scanPersistent(modifier: HybridBlock): HWallet = {
    log.debug(s"Applying modifier to wallet: ${Base58.encode(modifier.id)}")
    val changes = HBoxStoredState.changes(modifier).get

    val newBoxes = changes.toAppend.filter(s => secretByPublicImage(s.box.proposition).isDefined).map(_.box).map { box =>
      val boxTransaction = modifier.transactions.find(t => t.newBoxes.exists(tb => tb.id sameElements box.id))
      val txId = boxTransaction.map(_.id).getOrElse(Array.fill(32)(0: Byte))
      val ts = boxTransaction.map(_.timestamp).getOrElse(modifier.timestamp)
      val wb = WalletBox[PublicKey25519Proposition, PublicKey25519NoncedBox](box, txId, ts)(PublicKey25519NoncedBoxSerializer)
      ByteArrayWrapper(box.id) -> ByteArrayWrapper(wb.bytes)
    }

    val boxIdsToRemove = changes.toRemove.view.map(_.boxId).map(ByteArrayWrapper.apply)
    val newBoxIds: ByteArrayWrapper = ByteArrayWrapper(newBoxes.toArray.flatMap(_._1.data) ++
      boxIds.filter(bi => !boxIdsToRemove.exists(_.data sameElements bi)).flatten)
    store.update(ByteArrayWrapper(modifier.id), boxIdsToRemove, Seq(BoxIdsKey -> newBoxIds) ++ newBoxes)
    log.debug(s"Successfully applied modifier to wallet: ${Base58.encode(modifier.id)}")

    HWallet(seed, store, treasuryStore)
  }

  // TODO: is it ok that Secrets and TreasurySecrets are rolled back too?. Probably private keys should never been deleted.
  override def rollback(to: VersionTag): Try[HWallet] = Try {
    if (store.lastVersionID.exists(_.data sameElements to)) {
      this
    } else {
      log.debug(s"Rolling back wallet to: ${Base58.encode(to)}")
      store.rollback(ByteArrayWrapper(to))
      log.debug(s"Successfully rolled back wallet to: ${Base58.encode(to)}")
      HWallet(seed, store, treasuryStore)
    }
  }

  override type NVCT = this.type

}

object HWallet {

  def walletFile(settings: ScorexSettings): File = {
    settings.wallet.walletDir.mkdirs()

    new File(s"${settings.wallet.walletDir.getAbsolutePath}/wallet.dat")
  }

  def treasuryWalletFile(settings: ScorexSettings): File = {
    settings.wallet.walletDir.mkdirs()

    new File(s"${settings.wallet.walletDir.getAbsolutePath}/treasury_wallet.dat")
  }

  def exists(settings: ScorexSettings): Boolean = walletFile(settings).exists() && treasuryWalletFile(settings).exists()

  def readOrGenerate(settings: ScorexSettings, seed: ByteStr): HWallet = {
    val wFile = walletFile(settings)
    wFile.mkdirs()
    val trFile = treasuryWalletFile(settings)
    trFile.mkdirs()

    val boxesStorage = new LSMStore(wFile, maxJournalEntryCount = 10000, keepVersions = 100) //todo: configurable kV
    val treasuryStorage = new LSMStore(trFile, keySize = 8, maxJournalEntryCount = 10000, keepVersions = 100)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        boxesStorage.close()
        treasuryStorage.close()
      }
    })

    HWallet(seed, boxesStorage, treasuryStorage)
  }

  def readOrGenerate(settings: ScorexSettings): HWallet = {
    readOrGenerate(settings, settings.wallet.seed)
  }

  def readOrGenerate(settings: ScorexSettings, seed: ByteStr, accounts: Int): HWallet =
    (1 to accounts).foldLeft(readOrGenerate(settings, seed)) { case (w, _) =>
      w.generateNewSecret()
    }

  def readOrGenerate(settings: ScorexSettings, accounts: Int): HWallet =
    (1 to accounts).foldLeft(readOrGenerate(settings)) { case (w, _) =>
      w.generateNewSecret()
    }

  //wallet with applied initialBlocks
  def genesisWallet(settings: ScorexSettings, initialBlocks: Seq[HybridBlock]): HWallet = {
    initialBlocks.foldLeft(readOrGenerate(settings).generateNewSecret()) { (a, b) =>
      a.scanPersistent(b)
    }
  }
}