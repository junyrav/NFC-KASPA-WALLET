package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Mainnet market quote for indicative KRW/USD conversion. */
internal data class MarketQuote(val krw:Double,val usd:Double,val change:Double) {
    companion object {
        suspend fun fetch():MarketQuote=withContext(Dispatchers.IO) {
            val c=URL("https://api.coingecko.com/api/v3/simple/price?ids=kaspa&vs_currencies=krw,usd&include_24hr_change=true").openConnection() as HttpURLConnection
            c.connectTimeout=8000;c.readTimeout=8000;c.setRequestProperty("Accept","application/json")
            try {check(c.responseCode in 200..299);val o=JSONObject(c.inputStream.bufferedReader().use{it.readText()}).getJSONObject("kaspa")
                MarketQuote(o.getDouble("krw"),o.getDouble("usd"),o.optDouble("krw_24h_change",0.0)).also {require(it.krw.isFinite()&&it.krw>0&&it.usd.isFinite()&&it.usd>0)}
            } finally {c.disconnect()}
        }
    }
}
