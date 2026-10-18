package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

class CardSecurityTest {
    private val pub=KaspaAddress.xOnlyPublicKey(ByteArray(32).apply{this[31]=1})
    private fun record(slot:Slot=Slot.A,seq:Long=1)=CardRecord(slot,ByteArray(60){1},ByteArray(77){2},pub,seq,2)
    @Test fun metadataAndCiphertextCorruptionCannotPassStructuralChecks() {
        val original=record().encode()
        assertEquals(Slot.A,CardRecord.decode(original)!!.slot)
        for(i in 0 until 217){val bad=original.copyOf();bad[i]=(bad[i].toInt() xor 1).toByte();assertNull("byte $i",CardRecord.decode(bad))}
    }
    @Test fun powerLossAtEveryWriteRetainsAnIntactOriginalRecord() {
        repeat(20){failAt->
            val first=record(seq=1).encode();val old=first.copyOf();val second=ByteArray(288)
            var call=0
            runCatching{CardJournal.commit(CardSnapshot(first,second),record(seq=2),{bank,index,data->
                if(call++==failAt)error("power lost")
                data.copyInto(if(bank==0)first else second,index*16)
            },{bank->if(bank==0)first else second})}
            assertArrayEquals(old,first)
            val snapshot=CardSnapshot(first,second)
            assertTrue(snapshot.records.any{it.sequence==1L})
            assertTrue(snapshot.latest!!.sequence in 1L..2L)
        }
    }
    @Test fun journalAlternatesAndPreservesLastCommittedRecord() {
        val banks=arrayOf(record(seq=1).encode(),ByteArray(288))
        fun commit(seq:Long){CardJournal.commit(CardSnapshot(banks[0],banks[1]),record(seq=seq),{b,i,data->data.copyInto(banks[b],i*16)},{banks[it]})}
        commit(2);assertEquals(2L,CardRecord.decode(banks[1])!!.sequence)
        commit(3);assertEquals(3L,CardRecord.decode(banks[0])!!.sequence);assertEquals(2L,CardRecord.decode(banks[1])!!.sequence)
    }
    @Test fun approvalCannotBeReusedForDifferentCardContentsSlotOrAfterExpiry() {
        val bytes=record().encode()+ByteArray(288)
        val permit=OverwritePermit("010203",Crypto.sha(bytes),Slot.A,1000)
        assertTrue(permit.matches("010203",bytes,Slot.A,1001))
        assertFalse(permit.matches("040506",bytes,Slot.A,1001))
        assertFalse(permit.matches("010203",bytes,Slot.B,1001))
        assertFalse(permit.matches("010203",bytes,Slot.A,61001))
        assertFalse(permit.matches("010203",bytes.copyOf().apply{this[200]=3},Slot.A,1001))
    }
    @Test fun argonEncryptedShareIsBoundToWalletSlotAndSequence() {
        val pass="Correct-Backup!2026".toCharArray()
        val plain=Shares.split(ByteArray(32).apply{this[31]=1}).getValue(Slot.A)
        val rec=record()
        val encrypted=Crypto.passEnc(plain,pass,rec.header())
        val valid=rec.copy(recovery=encrypted)
        assertArrayEquals(plain,Crypto.passDec(valid,pass))
        for(tampered in listOf(valid.copy(slot=Slot.B),valid.copy(sequence=2),valid.copy(pub=ByteArray(32))))
            assertThrows(Exception::class.java){Crypto.passDec(tampered,pass)}
        pass.fill('\u0000')
    }
    @Test fun passwordPolicyRejectsWeakAndAcceptsStrongPasswords() {
        for(p in listOf("12345678","Password123456789!","aaaaaaaaAAAAAAAA1111","Correct-Backup!20".take(15)))assertFalse(p,PasswordPolicy.valid(p))
        assertTrue(PasswordPolicy.valid("Correct-Backup!2026"))
    }
    @Test fun mixingLegacyWalletsOrOmittingExpectedIdentityCannotPassRecovery() {
        val key=ByteArray(32).apply{this[31]=1};val other=ByteArray(32).apply{this[31]=2}
        val a=Shares.split(key);val b=Shares.split(other)
        val mixed=Shares.join(a.getValue(Slot.A),b.getValue(Slot.B))
        assertThrows(IllegalArgumentException::class.java){RecoveryIdentity.validate(mixed,KaspaAddress.mainnet(key),null)}
        assertThrows(IllegalArgumentException::class.java){RecoveryIdentity.validate(key,"",null)}
        assertThrows(IllegalArgumentException::class.java){RecoveryIdentity.validate(mixed,"",KaspaAddress.xOnlyPublicKey(key))}
        RecoveryIdentity.validate(key,"",KaspaAddress.xOnlyPublicKey(key))
    }

    @Test fun legacyCardCanBeRecoveredAndFirstMigrationPreservesIt() {
        val pass="old-pass".toCharArray();val salt=ByteArray(16){it.toByte()}
        val share=Shares.split(ByteArray(32).apply{this[31]=1}).getValue(Slot.A)
        val spec=javax.crypto.spec.PBEKeySpec(pass,salt,120000,256)
        val key=javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")
        spec.clearPassword()
        val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply{init(javax.crypto.Cipher.ENCRYPT_MODE,key)}
        val recovery=salt+cipher.iv+cipher.doFinal(share)
        val old=ByteArray(288)
        byteArrayOf(0x4e,0x4b,0x57,0x35,Slot.A.n.toByte(),60,77).copyInto(old)
        ByteArray(60){1}.copyInto(old,7);recovery.copyInto(old,67)
        old[239]=old.take(239).fold(0){a,b->a xor (b.toInt() and 255)}.toByte()
        val decoded=CardRecord.decode(old)!!
        assertEquals(1,decoded.version);assertArrayEquals(share,Crypto.passDec(decoded,pass))
        val original=old.copyOf();val banks=arrayOf(old,ByteArray(288))
        CardJournal.commit(CardSnapshot(banks[0],banks[1]),record(seq=1),{b,i,data->data.copyInto(banks[b],i*16)},{banks[it]})
        assertArrayEquals(original,banks[0]);assertEquals(2,CardRecord.decode(banks[1])!!.version)
        pass.fill('\u0000')
    }

}
