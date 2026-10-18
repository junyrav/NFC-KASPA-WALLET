package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class MainnetProtocolTest {
    private fun hex(s:String)=ByteArray(s.length/2){s.substring(it*2,it*2+2).toInt(16).toByte()}
    private fun text(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}

    // Independent vectors: kaspanet/rusty-kaspa crypto/addresses/src/lib.rs.
    @Test fun mainnetAddressMatchesOfficialVectors() {
        val zero="kaspa:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkx9awp4e"
        val pub=hex("5fff3c4da18f45adcdd499e44611e9fff148ba69db3c4ea2ddd955fc46a59522")
        val address="kaspa:qp0l70zd5x85ttwd6jv7g3s3a8llzj96d8dncn4zmhv4tlzx5k2jyqh70xmfj"
        assertEquals(zero,KaspaAddress.fromPublicKey(ByteArray(32)))
        assertArrayEquals(ByteArray(32),KaspaAddress.mainnetPayload(zero))
        assertEquals(address,KaspaAddress.fromPublicKey(pub));assertArrayEquals(pub,KaspaAddress.mainnetPayload(address))
    }
    @Test fun testnetPrefixAndCorruptedChecksumRejected() {
        val old="kaspatest:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqhqrxplya"
        assertThrows(IllegalArgumentException::class.java){KaspaAddress.mainnetPayload(old)}
        val address=KaspaAddress.mainnet(ByteArray(32).apply{this[31]=1})
        assertThrows(IllegalArgumentException::class.java){KaspaAddress.mainnetPayload(address.dropLast(1)+if(address.last()=='q')'p' else 'q')}
        assertThrows(IllegalArgumentException::class.java){KaspaAddress.mainnetPayload(address.replaceRange(8,9,"Q"))}
    }
    // Independent vector: bitcoin/bips bip-0340/test-vectors.csv, index 0.
    @Test fun schnorrMatchesBip340VectorAndRejectsTampering() {
        val key=ByteArray(32).apply{this[31]=3}
        val pub=hex("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9")
        val expected=hex("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0")
        assertArrayEquals(expected,KaspaTx.bip340Sign(ByteArray(32),key,ByteArray(32)))
        assertTrue(KaspaTx.bip340Verify(ByteArray(32),pub,expected))
        assertFalse(KaspaTx.bip340Verify(ByteArray(32).apply{this[0]=1},pub,expected))
    }
    // Independent SIG_HASH_ALL v0 vector: rusty-kaspa consensus/core/src/hashing/sighash.rs, native-all-0.
    @Test fun transactionHashMatchesKaspaCore() {
        val id=hex("880eb9819a31821d9d2399e2f35e2433b72637e393d71ecc9b8d0250f49153c3")
        val spk1=hex("208325613d2eeaf7176ac6c670b13c0043156c427438ed72d74b7800862ad884e8ac")
        val spk2=hex("20fcef4c106cf11135bbd70f02a726a92162d2fb8b22f0469126f800862ad884e8ac")
        val tx=KaspaTx.Tx(inputs=(0..2).map { i->KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(id,i.toLong()),(i+1)*100L,0,if(i==0)spk1 else spk2),sequence=i.toLong(),sigOpCount=0) },
            outputs=listOf(KaspaTx.Output(300,script=spk2),KaspaTx.Output(300,script=spk1)),lockTime=1615462089000L)
        assertEquals("03b7ac6927b2b67100734c3cc313ff8c2e8b3ce3e746d46dd660b706a916b1f5",text(KaspaTx.schnorrSigHash(tx,0)))
    }
    @Test fun signingRequiresOwnedInputsAndPositiveFee() {
        val key=ByteArray(32).apply{this[31]=1}
        val owner=KaspaTx.p2pkScript(KaspaAddress.xOnlyPublicKey(key))
        val input=KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32),0),1000000,0,owner))
        val tx=KaspaTx.Tx(inputs=listOf(input),outputs=listOf(KaspaTx.Output(990000,script=owner)))
        assertThrows(IllegalArgumentException::class.java){KaspaTx.signAll(tx,ByteArray(32).apply{this[31]=2})}
        assertTrue(input.signatureScript.isEmpty())
        KaspaTx.signAll(tx,key)
        assertEquals(66,input.signatureScript.size)
        assertTrue(KaspaTx.bip340Verify(KaspaTx.schnorrSigHash(tx,0),KaspaAddress.xOnlyPublicKey(key),input.signatureScript.copyOfRange(1,65)))
    }
}
