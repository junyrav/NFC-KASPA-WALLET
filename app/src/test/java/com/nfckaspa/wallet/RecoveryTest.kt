package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    @Test fun allPairsRecoverAndPreserveUnchangedCards() {
        repeat(32) {
            val secret=KaspaAddress.newPrivateKey()
            val original=Shares.split(secret)
            for((a,b) in listOf(Slot.A to Slot.B,Slot.A to Slot.C,Slot.B to Slot.C)) {
                assertArrayEquals(secret,Shares.join(original.getValue(a),original.getValue(b)))
                val restored=Shares.restore(original.getValue(a),original.getValue(b))
                Slot.entries.forEach { assertArrayEquals(original.getValue(it),restored.getValue(it)) }
                // Re-register A from the restored set; the untouched C must still recover.
                assertArrayEquals(secret,Shares.join(restored.getValue(Slot.A),original.getValue(Slot.C)))
            }
        }
    }
    @Test fun duplicateAndMalformedSharesRejected() {
        val a=Shares.split(KaspaAddress.newPrivateKey()).getValue(Slot.A)
        assertThrows(IllegalArgumentException::class.java) { Shares.join(a,a) }
        assertThrows(IllegalArgumentException::class.java) { Shares.join(a,byteArrayOf(2)) }
    }
    @Test fun generatedAddressRoundTrips() {
        repeat(32) {
            val key=KaspaAddress.newPrivateKey()
            assertArrayEquals(KaspaAddress.xOnlyPublicKey(key),KaspaAddress.testnetPayload(KaspaAddress.testnet(key)))
        }
    }
}
