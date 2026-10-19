package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves

/** The previous signer exists only as a test oracle, never as an app fallback. */
class NativeSignerTest {
    private fun bytes32(x:BigInteger)=x.toByteArray().takeLast(32).toByteArray().let{ByteArray(32-it.size)+it}
    private fun tagged(tag:String,b:ByteArray):ByteArray {val sha=MessageDigest.getInstance("SHA-256");val t=sha.digest(tag.toByteArray());return sha.digest(t+t+b)}
    private fun previousSign(msg:ByteArray,key:ByteArray,aux:ByteArray):ByteArray {
        val c=CustomNamedCurves.getByName("secp256k1");val n=c.n;val original=BigInteger(1,key);val p=c.g.multiply(original).normalize()
        val d=if(p.affineYCoord.toBigInteger().testBit(0))n-original else original
        val px=bytes32(p.affineXCoord.toBigInteger());val a=tagged("BIP0340/aux",aux);val scalar=bytes32(d)
        val t=ByteArray(32){(scalar[it].toInt() xor a[it].toInt()).toByte()}
        val nonce=BigInteger(1,tagged("BIP0340/nonce",t+px+msg)).mod(n)
        val r=c.g.multiply(nonce).normalize();val k=if(r.affineYCoord.toBigInteger().testBit(0))n-nonce else nonce
        val rx=bytes32(r.affineXCoord.toBigInteger());val e=BigInteger(1,tagged("BIP0340/challenge",rx+px+msg)).mod(n)
        return rx+bytes32((k+e*d).mod(n))
    }
    @Test fun nativeSignaturesAndAddressesMatchPreviousImplementationExactly() {
        val c=CustomNamedCurves.getByName("secp256k1")
        repeat(16){val key=KaspaAddress.newPrivateKey();val msg=Crypto.random(32);val aux=Crypto.random(32)
            assertArrayEquals(bytes32(c.g.multiply(BigInteger(1,key)).normalize().affineXCoord.toBigInteger()),KaspaAddress.xOnlyPublicKey(key))
            val sig=KaspaTx.bip340Sign(msg,key,aux)
            assertArrayEquals(previousSign(msg,key,aux),sig)
            assertTrue(KaspaTx.bip340Verify(msg,KaspaAddress.xOnlyPublicKey(key),sig));key.fill(0)
        }
    }
    @Test fun badKeysLengthsAndSignaturesFailWithoutCorruptingNativeContext() {
        val key=ByteArray(32).apply{this[31]=1};val msg=ByteArray(32);val aux=ByteArray(32)
        for(bad in listOf(ByteArray(32),ByteArray(32){0xff.toByte()},ByteArray(31))){
            assertFalse(NativeSecp.validKey(bad))
            assertThrows(IllegalArgumentException::class.java){NativeSecp.publicKey(bad)}
            assertThrows(IllegalArgumentException::class.java){NativeSecp.sign(msg,bad,aux)}
        }
        assertThrows(IllegalArgumentException::class.java){NativeSecp.sign(ByteArray(31),key,aux)}
        val sig=NativeSecp.sign(msg,key,aux);val pub=NativeSecp.publicKey(key)
        assertTrue(NativeSecp.verify(msg,pub,sig));assertFalse(NativeSecp.verify(msg,pub,sig.copyOf().apply{this[1]=(this[1].toInt() xor 1).toByte()}))
        assertFalse(NativeSecp.validPublicKey(ByteArray(32)))
    }
}
