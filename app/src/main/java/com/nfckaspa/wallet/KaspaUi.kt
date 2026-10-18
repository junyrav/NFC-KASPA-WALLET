package com.nfckaspa.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val KaspaTeal = Color(0xFF49B8A8)
internal val KaspaTealDark = Color(0xFF247C72)
internal val KaspaMint = Color(0xFFE8F7F4)
internal val KaspaSurface = Color(0xFFF4FBFA)
internal val KaspaInk = Color(0xFF102522)

private val KaspaColors = lightColorScheme(
    primary = KaspaTealDark,
    onPrimary = Color.White,
    primaryContainer = KaspaMint,
    onPrimaryContainer = KaspaInk,
    secondary = KaspaTeal,
    background = KaspaSurface,
    surface = Color.White,
    onBackground = KaspaInk,
    onSurface = KaspaInk
)

@Composable
internal fun KaspaWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KaspaColors, content = content)
}

@Composable
internal fun WalletHeader(balance: String, address: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KaspaMint, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kaspa NFC Wallet", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = KaspaInk)
            Spacer(Modifier.weight(1f))
            Surface(color = KaspaTeal, shape = RoundedCornerShape(50)) {
                Text("TN10", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("총 자산", color = KaspaTealDark)
        Text(balance, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = KaspaInk)
        Spacer(Modifier.height(4.dp))
        Text("KRW 환산은 다음 단계에서 실시간 시세와 연결", fontSize = 12.sp, color = KaspaTealDark)
        Spacer(Modifier.height(14.dp))
        Text(shortKaspaAddress(address), color = KaspaTealDark)
    }
}

@Composable
internal fun WalletActions(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onCopy: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
        Button(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("주소 복사") }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("새로고침") }
    }
}

@Composable
internal fun WalletSecurityStatus(cardA: Boolean, cardB: Boolean, cardC: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Wallet Security", fontWeight = FontWeight.Bold, color = KaspaInk)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SecurityChip("CARD A", cardA)
                SecurityChip("CARD B", cardB)
                SecurityChip("CARD C", cardC)
            }
            Spacer(Modifier.height(10.dp))
            Text("NFC 카드 + Galaxy 생체인증", fontSize = 13.sp, color = KaspaTealDark)
        }
    }
}

@Composable
private fun SecurityChip(label: String, active: Boolean) {
    Surface(
        color = if (active) KaspaMint else Color(0xFFF0F2F2),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            if (active) "$label ✓" else label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            color = if (active) KaspaTealDark else Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun VersionFooter() {
    Text(
        "v${BuildConfig.VERSION_NAME} · TN10 TESTNET ONLY",
        style = MaterialTheme.typography.bodySmall,
        color = KaspaTealDark
    )
}

internal fun shortKaspaAddress(address: String): String {
    if (address.length < 24) return address.ifBlank { "지갑을 생성하거나 복구하세요" }
    return address.take(16) + "…" + address.takeLast(10)
}
