package com.nfckaspa.wallet

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Low-level Kaspa transaction signing primitives.
 * TN10 only for now.
 */
internal object KaspaTx {
    data class Outpoint(val transactionId: ByteArray, val index: Long)
    data class Utxo(val outpoint: Outpoint, val amount: Long, val scriptVersion: Int, val script: ByteArray)
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

    private val curve=CustomNamedCurves.getByName("secp256k1")
    private val n:BigInteger=curve.n
    private val g=curve.g
    private fun bytes32(x:BigInteger):ByteArray{
        val a=x.toByteArray();return when{a.size==32->a;a.size<32->ByteArray(32-a.size)+a;else->a.copyOfRange(a.size-32,a.size)}
    }
    private fun xor(a:ByteArray,b:ByteArray)=ByteArray(a.size){i->(a[i].toInt() xor b[i].toInt()).toByte()}
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b)
    private fun tagged(tag:String,msg:ByteArray):ByteArray{val t=sha(tag.toByteArray(Charsets.US_ASCII));return sha(t+t+msg)}

    /** BIP-340 Schnorr signature used by Kaspa P2PK. */
    fun bip340Sign(msg32:ByteArray,privateKey:ByteArray):ByteArray{
        require(msg32.size==32&&privateKey.size==32)
        val d0=BigInteger(1,privateKey);require(d0>BigInteger.ZERO&&d0<n)
        val p=g.multiply(d0).normalize();val d=if(p.affineYCoord.toBigInteger().testBit(0))n.subtract(d0) else d0
        val px=bytes32(p.affineXCoord.toBigInteger())
        val aux=ByteArray(32).also{SecureRandom().nextBytes(it)}
        val t=xor(bytes32(d),tagged("BIP0340/aux",aux))
        val k0=BigInteger(1,tagged("BIP0340/nonce",t+px+msg32)).mod(n);require(k0!=BigInteger.ZERO)
        val rPoint=g.multiply(k0).normalize();val k=if(rPoint.affineYCoord.toBigInteger().testBit(0))n.subtract(k0) else k0
        val rx=bytes32(rPoint.affineXCoord.toBigInteger())
        val e=BigInteger(1,tagged("BIP0340/challenge",rx+px+msg32)).mod(n)
        return rx+bytes32(k.add(e.multiply(d)).mod(n))
    }

    fun signAll(tx:Tx,privateKey:ByteArray){
        tx.inputs.forEachIndexed{idx,input->
            val sig=bip340Sign(schnorrSigHash(tx,idx),privateKey)
            input.signatureScript=byteArrayOf(65)+sig+byteArrayOf(1)
        }
    }

    fun p2pkScript(xOnlyPubKey:ByteArray):ByteArray{require(xOnlyPubKey.size==32);return byteArrayOf(0x20)+xOnlyPubKey+byteArrayOf(0xac.toByte())}
}
