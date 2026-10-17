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
        val tag = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) onTag?.invoke(tag)
    }
}

@Composable
fun WalletScreen(registerListener: (((Tag) -> Unit) -> Unit)) {
    var status by remember { mutableStateOf("NFC 태그를 휴대폰 뒤에 대주세요") }
    var uid by remember { mutableStateOf("-") }
    var type by remember { mutableStateOf("-") }
    var size by remember { mutableStateOf("-") }

    LaunchedEffect(Unit) {
        registerListener { tag ->
            uid = tag.id.joinToString("") { "%02X".format(it) }
            val mc = MifareClassic.get(tag)
            if (mc != null) {
                type = "MIFARE Classic"
                size = "${mc.size} bytes / ${mc.sectorCount} sectors / ${mc.blockCount} blocks"
                status = "태그 인식 완료 ✓"
            } else {
                type = tag.techList.joinToString()
                size = "확인 필요"
                status = "NFC 인식 완료"
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(28.dp))
                Text("NFC KASPA WALLET", style = MaterialTheme.typography.headlineMedium)
                Text("TESTNET · v0.1", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(36.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp)) {
                        Text("0.00000000 KAS", style = MaterialTheme.typography.headlineLarge)
                        Text("₩ 0   ·   $ 0")
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Button(onClick = { status = "v0.2에서 Testnet 송금 기능을 활성화합니다" }) { Text("보내기") }
                            OutlinedButton(onClick = { status = "v0.2에서 QR 받기 기능을 활성화합니다" }) { Text("받기") }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("NFC CARD TEST", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(status)
                        Spacer(Modifier.height(16.dp))
                        Text("UID: $uid")
                        Text("Type: $type")
                        Text("Memory: $size")
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("A ○    B ○    C ○")
                Spacer(Modifier.height(8.dp))
                Text("송금: NFC 1장 + 생체인증\n복구: NFC 3장 중 2장 + Recovery Password")
                Spacer(Modifier.weight(1f))
                Text("실제 KAS를 입금하지 마세요 · Testnet 개발용")
            }
        }
    }
}
