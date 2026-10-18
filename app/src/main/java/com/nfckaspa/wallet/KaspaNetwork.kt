package com.nfckaspa.wallet

import java.net.HttpURLConnection
import java.net.URL

/** Every network operation is pinned to the same mainnet service. */
internal object KaspaNetwork {
    const val API="https://api.kaspa.org"
    const val EXPLORER="https://explorer.kaspa.org"
    class HttpFailure(val code:Int):IllegalStateException("메인넷 요청 실패 · HTTP $code")
    fun request(method:String,path:String,body:String?=null):String {
        require(path.startsWith("/")&&!path.startsWith("//"))
        val c=URL(API+path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout=15000;c.readTimeout=20000;c.requestMethod=method
            c.setRequestProperty("Accept","application/json")
            c.setRequestProperty("User-Agent","Kaspa-NFC-Wallet/${BuildConfig.VERSION_NAME}")
            if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
            val code=c.responseCode
            val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299)throw HttpFailure(code)
            return text
        } finally { c.disconnect() }
    }
}
