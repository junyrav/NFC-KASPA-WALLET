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
        Spacer(Modifier.height(12.dp))
        Text(shortKaspaAddress(address), color = KaspaTealDark)
    }
}

internal fun shortKaspaAddress(address: String): String {
    if (address.length < 24) return address.ifBlank { "지갑을 생성하거나 복구하세요" }
    return address.take(16) + "…" + address.takeLast(10)
}
