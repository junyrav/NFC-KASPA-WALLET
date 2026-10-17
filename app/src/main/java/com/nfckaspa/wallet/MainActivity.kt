package com.nfckaspa.wallet

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var onTag: ((Tag) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent { WalletScreen { onTag = it } }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pi, null, null)
    }

    override fun onPause() {
        nfcAdapter?.disableForegroundDispatch(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag != null) onTag?.invoke(tag)
    }
}

private enum class CardSlot(val code: Byte, val label: String) {
    A(1, "A"), B(2, "B"), C(3, "C")
}

private data class CardReadResult(
    val ok: Boolean,
    val slot: CardSlot? = null,
    val message: String
)

private object NfcCardStore {
    // Sector 1 only. Sector 0 / manufacturer block is never written.
    private const val SECTOR = 1
    private val defaultKey = MifareClassic.KEY_DEFAULT
    private val magic = byteArrayOf('N'.code.toByte(), 'K'.code.toByte(), 'W'.code.toByte(), '2'.code.toByte())

    fun writeAndVerify(tag: Tag, slot: CardSlot): CardReadResult {
        val mc = MifareClassic.get(tag) ?: return CardReadResult(false, message = "MIFARE Classic 카드가 아닙니다")
        return try {
            mc.connect()
            if (!mc.authenticateSectorWithKeyA(SECTOR, defaultKey)) {
                return CardReadResult(false, message = "Sector 1 인증 실패 · 기본 Key A가 아닐 수 있습니다")
            }

            val firstBlock = mc.sectorToBlock(SECTOR)
            val block = ByteArray(MifareClassic.BLOCK_SIZE)
            System.arraycopy(magic, 0, block, 0, magic.size)
            block[4] = slot.code
            val marker = "TESTNET".toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(marker, 0, block, 5, marker.size.coerceAtMost(10))
            block[15] = checksum(block, 15)

            // Only first data block of sector 1. Never touch the sector trailer.
            mc.writeBlock(firstBlock, block)
            val verify = mc.readBlock(firstBlock)
            if (!verify.contentEquals(block)) {
                CardReadResult(false, message = "쓰기 후 검증 실패")
            } else {
                CardReadResult(true, slot, "CARD ${slot.label} 등록 및 검증 완료 ✓")
            }
        } catch (e: Exception) {
            CardReadResult(false, message = "NFC 오류: ${e.javaClass.simpleName}")
        } finally {
            try { mc.close() } catch (_: Exception) {}
        }
    }

    fun read(tag: Tag): CardReadResult {
        val mc = MifareClassic.get(tag) ?: return CardReadResult(false, message = "MIFARE Classic 카드가 아닙니다")
        return try {
            mc.connect()
            if (!mc.authenticateSectorWithKeyA(SECTOR, defaultKey)) {
                return CardReadResult(false, message = "카드는 읽혔지만 Sector 1 인증에 실패했습니다")
            }
            val block = mc.readBlock(mc.sectorToBlock(SECTOR))
            if (!block.copyOfRange(0, 4).contentEquals(magic)) {
                return CardReadResult(false, message = "NFC 인식 완료 · 아직 NFC KASPA 카드로 등록되지 않았습니다")
            }
            if (block[15] != checksum(block, 15)) {
                return CardReadResult(false, message = "등록 데이터 체크섬 오류")
            }
            val slot = CardSlot.entries.firstOrNull { it.code == block[4] }
                ?: return CardReadResult(false, message = "알 수 없는 카드 슬롯")
            CardReadResult(true, slot, "CARD ${slot.label} 확인 완료 ✓")
        } catch (e: Exception) {
            CardReadResult(false, message = "NFC 오류: ${e.javaClass.simpleName}")
        } finally {
            try { mc.close() } catch (_: Exception) {}
        }
    }

    private fun checksum(data: ByteArray, length: Int): Byte {
        var v = 0
        for (i in 0 until length) v = v xor (data[i].toInt() and 0xFF)
        return v.toByte()
    }
}

@Composable
fun WalletScreen(registerListener: (((Tag) -> Unit) -> Unit)) {
    var status by remember { mutableStateOf("카드 A/B/C를 선택하거나 NFC 카드를 태그하세요") }
    var uid by remember { mutableStateOf("-") }
    var type by remember { mutableStateOf("-") }
    var size by remember { mutableStateOf("-") }
    var pendingSlot by remember { mutableStateOf<CardSlot?>(null) }
    var cardA by remember { mutableStateOf(false) }
    var cardB by remember { mutableStateOf(false) }
    var cardC by remember { mutableStateOf(false) }

    fun mark(slot: CardSlot) {
        when (slot) {
            CardSlot.A -> cardA = true
            CardSlot.B -> cardB = true
            CardSlot.C -> cardC = true
        }
    }

    LaunchedEffect(Unit) {
        registerListener { tag ->
            uid = tag.id.joinToString("") { "%02X".format(it) }
            val mc = MifareClassic.get(tag)
            if (mc != null) {
                type = "MIFARE Classic"
                size = "${mc.size} bytes / ${mc.sectorCount} sectors / ${mc.blockCount} blocks"
                val selected = pendingSlot
                val result = if (selected != null) NfcCardStore.writeAndVerify(tag, selected) else NfcCardStore.read(tag)
                status = result.message
                if (result.ok && result.slot != null) mark(result.slot)
                if (selected != null) pendingSlot = null
            } else {
                type = tag.techList.joinToString()
                size = "확인 필요"
                status = "이 버전은 MIFARE Classic 카드 쓰기만 지원합니다"
                pendingSlot = null
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(20.dp))
                Text("NFC KASPA WALLET", style = MaterialTheme.typography.headlineMedium)
                Text("TESTNET · v0.2 NFC WRITE TEST", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(24.dp))

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("NFC CARD REGISTRATION", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        Text("등록할 카드를 선택한 뒤 해당 NFC 카드를 휴대폰 뒤에 대세요.")
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Button(onClick = { pendingSlot = CardSlot.A; status = "CARD A를 휴대폰 뒤에 대세요" }) { Text("A ${if (cardA) "✓" else "○"}") }
                            Button(onClick = { pendingSlot = CardSlot.B; status = "CARD B를 휴대폰 뒤에 대세요" }) { Text("B ${if (cardB) "✓" else "○"}") }
                            Button(onClick = { pendingSlot = CardSlot.C; status = "CARD C를 휴대폰 뒤에 대세요" }) { Text("C ${if (cardC) "✓" else "○"}") }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(status)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("LAST NFC", style = MaterialTheme.typography.titleMedium)
                        Text("UID: $uid")
                        Text("Type: $type")
                        Text("Memory: $size")
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("이번 버전은 Sector 1의 데이터 블록 1개만 사용합니다.")
                Text("UID/Manufacturer Block 및 Sector Trailer는 변경하지 않습니다.")
                Spacer(Modifier.height(12.dp))
                Text("다음 단계: 생체인증 + Android Keystore 암호화")
                Spacer(Modifier.weight(1f))
                Text("실제 Seed/Private Key/KAS를 사용하지 마세요 · Testnet 개발용")
            }
        }
    }
}
