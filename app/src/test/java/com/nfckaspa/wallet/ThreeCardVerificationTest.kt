package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class ThreeCardVerificationTest {
    private val key=ByteArray(32).apply{this[31]=1}
    private val wallet=KaspaAddress.mainnet(key)
    @Test fun allThreeAreRequiredForVerificationWhileAnyTwoStillRecover() {
        val shares=Shares.split(key)
        ThreeCardVerification.validate(shares,wallet,KaspaAddress.xOnlyPublicKey(key))
        for(missing in Slot.entries){
            val pair=shares.filterKeys{it!=missing}
            assertThrows(IllegalArgumentException::class.java){ThreeCardVerification.validate(pair,wallet,null)}
            val recovered=Shares.join(pair.values.first(),pair.values.last())
            assertArrayEquals(key,recovered);recovered.fill(0)
        }
        shares.values.forEach{it.fill(0)}
    }
    @Test fun inconsistentThirdShareOrWrongSlotCannotPassTheThreeCardCheck() {
        val shares=Shares.split(key);val different=Shares.split(key)
        assertThrows(IllegalArgumentException::class.java){ThreeCardVerification.validate(shares+(Slot.C to different.getValue(Slot.C)),wallet,null)}
        assertThrows(IllegalArgumentException::class.java){ThreeCardVerification.validate(shares+(Slot.C to shares.getValue(Slot.A)),wallet,null)}
        shares.values.forEach{it.fill(0)};different.values.forEach{it.fill(0)}
    }
}
