package com.nfckaspa.wallet

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

@Composable
internal fun WalletHome(
    balance: String,
    address: String,
    krwValue: String = "--",
    price: String = "--",
    change24h: String = "--",
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onCopy: () -> Unit,
    onRefresh: () -> Unit,
    onWallet: () -> Unit = {},
    onTransactions: () -> Unit = {},
    onCards: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Kaspa NFC Wallet", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = KaspaInk)
                Text("Secure NFC wallet", fontSize = 12.sp, color = KaspaTealDark)
            }
            Spacer(Modifier.weight(1f))
            Surface(color = KaspaTeal, shape = RoundedCornerShape(50)) {
                Text("메인넷", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = KaspaMint),
            shape = RoundedCornerShape(26.dp)
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("TOTAL BALANCE", fontSize = 12.sp, color = KaspaTealDark, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(balance, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = KaspaInk)
                Text("≈ ₩$krwValue", fontSize = 18.sp, color = KaspaTealDark)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("1 KAS = ₩$price", fontSize = 13.sp, color = KaspaTealDark)
                    Spacer(Modifier.weight(1f))
                    Text("$change24h · 24h", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = KaspaTealDark)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSend, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Send", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Receive", fontWeight = FontWeight.Bold) }
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Wallet address", fontSize = 12.sp, color = KaspaTealDark)
                Spacer(Modifier.height(5.dp))
                Text(shortKaspaAddress(address), fontWeight = FontWeight.SemiBold, color = KaspaInk)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy") }
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) { Text("Refresh") }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NavItem("Wallet", true, onWallet)
            NavItem("Transactions", false, onTransactions)
            NavItem("Cards", false, onCards)
            NavItem("Settings", false, onSettings)
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = if (selected) KaspaTealDark else Color.Gray)
    }
}
