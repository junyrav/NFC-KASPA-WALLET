package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** TN10 TESTNET ONLY. This client refuses non-TN10 endpoints. */
internal object Tn10Broadcast {
    private const val BASE = "https://api-tn10.kaspa.org"

    suspend fun postTransaction(testnetJson: String): String = withContext(Dispatchers.IO) {
        require(BASE.contains("api-tn10"))
        val c = URL("$BASE/transactions").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "NFC-KASPA-WALLET/0.6.9-TN10")
        c.outputStream.use { it.write(testnetJson.toByteArray()) }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("TN10 HTTP $code ${body.take(500)}")
            Regex("\\\"transactionId\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"").find(body)?.groupValues?.get(1)
                ?: throw IllegalStateException("TN10 transactionId 없음: ${body.take(300)}")
        } finally { c.disconnect() }
    }
}
