package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

/** Independent official bitcoin/bips bip-0340/test-vectors.csv, 32-byte messages 0–14. */
class Bip340VectorsTest {
    private fun hex(s:String)=ByteArray(s.length/2){s.substring(it*2,it*2+2).toInt(16).toByte()}
    @Test fun allOfficial32ByteVectorsIncludingInvalidPointsAndSignatures() {
        val lines=javaClass.getResourceAsStream("/bip340-vectors-0-14.csv")!!.bufferedReader().use{it.readLines()}.drop(1).filter{it.isNotBlank()}
        assertEquals(15,lines.size)
        lines.forEach { line->
            val v=line.split(',');val pub=hex(v[2]);val aux=hex(v[3]);val msg=hex(v[4]);val signature=hex(v[5]);val valid=v[6]=="TRUE"
            assertEquals("vector ${v[0]}",valid,KaspaTx.bip340Verify(msg,pub,signature))
            if(v[1].isNotBlank())assertArrayEquals("signing vector ${v[0]}",signature,KaspaTx.bip340Sign(msg,hex(v[1]),aux))
        }
    }
}
