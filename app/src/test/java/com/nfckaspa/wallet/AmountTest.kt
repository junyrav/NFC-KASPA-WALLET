package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class AmountTest {
    @Test fun preciseSompiAmounts() {
        assertEquals(1L,KaspaTransaction.kasToSompi("0.00000001"))
        assertEquals(123456789L,KaspaTransaction.kasToSompi("1.23456789"))
        assertEquals(100000000L,KaspaTransaction.kasToSompi("1"))
        assertEquals(10000000L,KaspaTransaction.kasToSompi("0.1"))
    }
    @Test fun invalidAndOverflowAmountsRejected() {
        listOf("0","0.00000000","-1","NaN","Infinity","1e3","0.000000001","1,000","abc").forEach { s ->
            assertThrows("Invalid amount $s",IllegalArgumentException::class.java) { KaspaTransaction.kasToSompi(s) }
        }
        assertThrows(ArithmeticException::class.java) { KaspaTransaction.kasToSompi("9223372036854775807") }
    }
}
