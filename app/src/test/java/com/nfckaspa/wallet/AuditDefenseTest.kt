package com.nfckaspa.wallet

import org.junit.Assert.*
import org.junit.Test

/**
 * 감사 방어 확인 테스트 (acdd1c4 기준).
 * 이 파일의 테스트가 "통과"하면 = 해당 방어가 현재 코드에서 동작함을 확인했다는 뜻입니다.
 * 기대값은 rusty-kaspa v2.1.0(01b532e) 소스의 테스트 벡터 또는 그 공식에서 직접 계산한 값입니다.
 */
class AuditDefenseTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun text(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val key = ByteArray(32).apply { this[31] = 1 }
    private val from = KaspaAddress.mainnet(key)
    private val to = KaspaAddress.mainnet(ByteArray(32).apply { this[31] = 2 })
    private fun utxo(amount: Long, index: Long = 0) = KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32), index), amount, 0, KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(from)))

    // rusty-kaspa consensus/core/src/hashing/tx.rs test_transaction_hashing #1~#3 (v0, gas 0, native)
    @Test fun defense_v0TransactionIdMatchesCoreVectorsBeyondEmptyTx() {
        val input = KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(ByteArray(32), 2), 1, 0, byteArrayOf()), sequence = 7, signatureScript = byteArrayOf(1, 2), sigOpCount = 5)
        val t2 = KaspaTx.Tx(inputs = listOf(input), outputs = emptyList())
        assertEquals("b2d65ae36e123eb73f253176d7234a57656b84d0d60b9fc746ab0d0f085c9cc7", KaspaTx.transactionId(t2))
        val t3 = t2.copy(outputs = listOf(KaspaTx.Output(1564, 7, byteArrayOf(1, 2, 3, 4, 5))))
        assertEquals("67289b12146d1b5ef384332137399791a5cfe89506ff31688b0d95ae821d0a0c", KaspaTx.transactionId(t3))
    }

    // rusty-kaspa consensus/core/src/hashing/sighash.rs: native-all-0-modify-{input-1,output-1,sequence-1}
    @Test fun defense_v0SigHashMatchesAdditionalCoreVectors() {
        val id = hex("880eb9819a31821d9d2399e2f35e2433b72637e393d71ecc9b8d0250f49153c3")
        val spk1 = hex("208325613d2eeaf7176ac6c670b13c0043156c427438ed72d74b7800862ad884e8ac")
        val spk2 = hex("20fcef4c106cf11135bbd70f02a726a92162d2fb8b22f0469126f800862ad884e8ac")
        fun tx(index1: Long = 1, seq1: Long = 1, out1: Long = 300) = KaspaTx.Tx(
            inputs = (0..2).map { i -> KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(id, if (i == 1) index1 else i.toLong()), (i + 1) * 100L, 0, if (i == 0) spk1 else spk2), sequence = if (i == 1) seq1 else i.toLong(), sigOpCount = 0) },
            outputs = listOf(KaspaTx.Output(300, script = spk2), KaspaTx.Output(out1, script = spk1)), lockTime = 1615462089000L)
        assertEquals("03b7ac6927b2b67100734c3cc313ff8c2e8b3ce3e746d46dd660b706a916b1f5", text(KaspaTx.schnorrSigHash(tx(), 0)))
        assertEquals("a9f563d86c0ef19ec2e4f483901d202e90150580b6123c3d492e26e7965f488c", text(KaspaTx.schnorrSigHash(tx(index1 = 2), 0)))
        assertEquals("aad2b61bd2405dfcf7294fc2be85f325694f02dda22d0af30381cb50d8295e0a", text(KaspaTx.schnorrSigHash(tx(out1 = 100), 0)))
        assertEquals("0818bd0a3703638d4f01014c92cf866a8903cab36df2fa2506dc0d06b94295e8", text(KaspaTx.schnorrSigHash(tx(seq1 = 12345), 0)))
    }

    // 악성 API 가 UTXO 금액을 낮춰 보고해도 서명 해시가 금액에 묶여 있어 노드에서 서명이 무효가 된다.
    @Test fun defense_sigHashCommitsToReportedUtxoAmountAndScript() {
        val p = KaspaTransaction.build(from, to, 100_00000000L, listOf(utxo(200_00000000L)), 100.0, KaspaMass::calculate)
        val real = KaspaTx.schnorrSigHash(p.tx, 0)
        val lied = p.tx.copy(inputs = listOf(KaspaTx.Input(utxo(199_00000000L))))
        assertFalse(real.contentEquals(KaspaTx.schnorrSigHash(lied, 0)))
    }

    // KaspaMass 가 rusty-kaspa v2.1.0 공식(compute / KIP-9 storage / 정규화 transient=2·size)과 일치
    @Test fun defense_localMassMatchesToccataFormulaForP2pk() {
        fun tx(ins: List<Long>, outs: List<Long>) = KaspaTx.Tx(inputs = ins.mapIndexed { i, a -> KaspaTx.Input(utxo(a, i.toLong())) }, outputs = outs.map { KaspaTx.Output(it, script = utxo(1).script) })
        val p = KaspaMass.parts(tx(listOf(200_000_000), listOf(100_000_000, 99_950_000)))
        assertEquals(KaspaMass.Parts(2036, 15005, 632), p)               // size 316, storage C/1e8 + C/99950000 − C/2e8
        assertEquals(1624L, KaspaMass.calculate(tx(listOf(20_000_000), listOf(19_837_600))))
        // 3 입력·2 출력: 산술 평균 경로 (|I|>2)
        val three = KaspaMass.parts(tx(listOf(1_00000000, 1_00000000, 1_00000000), listOf(2_00000000, 99_000_000)))
        assertEquals(94L + 3 * 118 + 2 * 52 + 2 * 360 + 3 * 1000, three.compute)
        assertEquals(maxOf(0L, (1_000_000_000_000L / 2_00000000 + 1_000_000_000_000L / 99_000_000) - 3 * (1_000_000_000_000L / 1_00000000)), three.storage)
    }

    // 수수료 하한: Toccata 이후 노드 정책 100 sompi × max(compute, 2·bytes) 이상인지
    @Test fun defense_feeMeetsPostToccataRelayFloor() {
        val p = KaspaTransaction.build(from, to, 100_00000000L, listOf(utxo(200_00000000L)), 100.0, KaspaMass::calculate)
        val parts = KaspaMass.parts(p.tx)
        assertTrue(p.fee >= 100 * maxOf(parts.compute, parts.transient))
    }

    // 같은 슬롯 복제·중복 조각은 서로 다른 두 조각으로 인정되지 않는다
    @Test fun defense_clonedSameSlotShareCannotCountTwice() {
        val a = Shares.split(key).getValue(Slot.A)
        assertThrows(IllegalArgumentException::class.java) { Shares.join(a, a.copyOf()) }
    }
}
