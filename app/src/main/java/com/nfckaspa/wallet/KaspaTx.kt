package com.nfckaspa.wallet

import org.bouncycastle.crypto.digests.Blake2bDigest
import java.security.SecureRandom

/**
 * Low-level Kaspa transaction signing primitives.
 * Version-0 native P2PK transactions on Kaspa mainnet.
 */
internal object KaspaTx {
    data class Outpoint(val transactionId: ByteArray, val index: Long)
    data class Utxo(val outpoint: Outpoint, val amount: Long, val scriptVersion: Int, val script: ByteArray, val blockDaaScore:Long=0, val isCoinbase:Boolean=false)
    data class Input(val utxo: Utxo, val sequence: Long = 0L, var signatureScript: ByteArray = byteArrayOf(), val sigOpCount: Int = 1)
    data class Output(val amount: Long, val scriptVersion: Int = 0, val script: ByteArray)
    data class Tx(val version: Int = 0, val inputs: List<Input>, val outputs: List<Output>, val lockTime: Long = 0L)

    private val zero32 = ByteArray(32)
    private val nativeSubnet = ByteArray(20)
    private val signingDomain = "TransactionSigningHash".toByteArray(Charsets.US_ASCII)

    private fun le16(v:Int)=byteArrayOf((v and 255).toByte(),((v ushr 8) and 255).toByte())
    private fun le32(v:Long)=ByteArray(4){i->((v ushr (8*i)) and 255).toByte()}
    private fun le64(v:Long)=ByteArray(8){i->((v ushr (8*i)) and 255).toByte()}
    private fun variable(b:ByteArray)=le64(b.size.toLong())+b

    /** Kaspa TransactionSigningHash: keyed BLAKE2b-256. */
    private fun signingHash(vararg chunks:ByteArray):ByteArray{
        // BC's keyed constructor takes digest length in BYTES (1..64), not bits.
        val d=Blake2bDigest(signingDomain,32,null,null)
        chunks.forEach{d.update(it,0,it.size)}
        return ByteArray(32).also{d.doFinal(it,0)}
    }

    private fun previousOutputsHash(tx:Tx)=signingHash(*tx.inputs.flatMap{listOf(it.utxo.outpoint.transactionId,le32(it.utxo.outpoint.index))}.toTypedArray())
    private fun sequencesHash(tx:Tx)=signingHash(*tx.inputs.map{le64(it.sequence)}.toTypedArray())
    private fun sigOpsHash(tx:Tx)=signingHash(byteArrayOf(*tx.inputs.map{it.sigOpCount.toByte()}.toByteArray()))
    private fun script(v:Int,s:ByteArray)=le16(v)+variable(s)
    private fun outputsHash(tx:Tx)=signingHash(*tx.outputs.flatMap{listOf(le64(it.amount),script(it.scriptVersion,it.script))}.toTypedArray())

    /** SIG_HASH_ALL (0x01), version-0 native transaction. */
    fun schnorrSigHash(tx:Tx,inputIndex:Int):ByteArray{
        require(tx.version==0){"v0 signing path only"}
        val i=tx.inputs[inputIndex]
        return signingHash(
            le16(tx.version), previousOutputsHash(tx), sequencesHash(tx), sigOpsHash(tx),
            i.utxo.outpoint.transactionId, le32(i.utxo.outpoint.index),
            script(i.utxo.scriptVersion,i.utxo.script), le64(i.utxo.amount), le64(i.sequence), byteArrayOf(i.sigOpCount.toByte()),
            outputsHash(tx), le64(tx.lockTime), nativeSubnet, le64(0), zero32, byteArrayOf(1)
        )
    }

    /** BIP-340: pinned upstream libsecp256k1, with fresh auxiliary randomness in production. */
    fun bip340Sign(msg32:ByteArray,privateKey:ByteArray,aux:ByteArray=ByteArray(32).also{SecureRandom().nextBytes(it)}):ByteArray =
        NativeSecp.sign(msg32,privateKey,aux)
    internal fun bip340Verify(msg:ByteArray,pub:ByteArray,sig:ByteArray):Boolean=NativeSecp.verify(msg,pub,sig)

    fun signAll(tx:Tx,privateKey:ByteArray){
        require(tx.version==0&&tx.inputs.isNotEmpty()&&tx.outputs.isNotEmpty()){"지원하지 않는 거래 형식입니다"}
        val pub=KaspaAddress.xOnlyPublicKey(privateKey)
        val owner=p2pkScript(pub)
        tx.inputs.forEach{ require(it.sigOpCount==1&&it.utxo.amount>0&&it.utxo.scriptVersion==0&&it.utxo.script.contentEquals(owner)){"현재 지갑의 UTXO가 아닙니다"} }
        tx.outputs.forEach{require(it.amount>0&&it.scriptVersion==0&&it.script.size==34&&it.script[0]==0x20.toByte()&&it.script[33]==0xac.toByte()&&KaspaAddress.isValidXOnlyPublicKey(it.script.copyOfRange(1,33))){"출력 주소 또는 금액 오류"}}
        val totalIn=tx.inputs.fold(0L){sum,i->Math.addExact(sum,i.utxo.amount)}
        val totalOut=tx.outputs.fold(0L){sum,o->Math.addExact(sum,o.amount)}
        require(totalIn>totalOut){"거래 금액 또는 수수료 오류"}
        FeePolicy.check(totalIn-totalOut,tx.outputs.first().amount,KaspaMass.calculate(tx))
        tx.inputs.forEachIndexed{idx,input->
            val hash=schnorrSigHash(tx,idx)
            val sig=bip340Sign(hash,privateKey)
            check(bip340Verify(hash,pub,sig)){"서명 검증 실패"}
            input.signatureScript=byteArrayOf(65)+sig+byteArrayOf(1)
        }
    }

    /** Version-0 native TransactionID: excludes signatures, sigop counts and mass commitment. */
    fun transactionId(tx:Tx):String {
        require(tx.version==0)
        val chunks=ArrayList<ByteArray>()
        chunks+=le16(tx.version);chunks+=le64(tx.inputs.size.toLong())
        tx.inputs.forEach{chunks+=it.utxo.outpoint.transactionId;chunks+=le32(it.utxo.outpoint.index);chunks+=le64(0);chunks+=le64(it.sequence)}
        chunks+=le64(tx.outputs.size.toLong())
        tx.outputs.forEach{chunks+=le64(it.amount);chunks+=script(it.scriptVersion,it.script)}
        chunks+=le64(tx.lockTime);chunks+=nativeSubnet;chunks+=le64(0);chunks+=le64(0)
        val digest=Blake2bDigest("TransactionID".toByteArray(Charsets.US_ASCII),32,null,null)
        chunks.forEach{digest.update(it,0,it.size)}
        return ByteArray(32).also{digest.doFinal(it,0)}.joinToString(""){"%02x".format(it.toInt() and 255)}
    }

    fun p2pkScript(xOnlyPubKey:ByteArray):ByteArray{require(xOnlyPubKey.size==32);return byteArrayOf(0x20)+xOnlyPubKey+byteArrayOf(0xac.toByte())}
}
