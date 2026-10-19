package com.nfckaspa.wallet

import java.net.HttpURLConnection
import java.net.URL

/** Mainnet REST transport; signing-critical data is additionally checked by NodeQuorum. */
internal object KaspaNetwork {
    const val API="https://api.kaspa.org"
    const val MAX_BODY=4*1024*1024
    const val EXPLORER="https://explorer.kaspa.org"
    class HttpFailure(val code:Int):IllegalStateException("메인넷 요청 실패 · HTTP $code")
    fun request(method:String,path:String,body:String?=null):String {
        require(path.startsWith("/")&&!path.startsWith("//"))
        return fetch(API+path,method,body,15000,20000)
    }
    internal fun resolver(url:String)=fetch(url,"GET",null,1500,2000)
    private fun fetch(url:String,method:String,body:String?,connect:Int,read:Int):String {
        val c=URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout=connect;c.readTimeout=read;c.requestMethod=method;c.instanceFollowRedirects=false
            c.setRequestProperty("Accept","application/json")
            c.setRequestProperty("User-Agent","Kaspa-NFC-Wallet/${BuildConfig.VERSION_NAME}")
            if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
            val code=c.responseCode
            val text=(if(code in 200..299)c.inputStream else c.errorStream)?.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var count:Int;while(input.read(buffer).also{count=it}!=-1){check(out.size()+count<=MAX_BODY){"네트워크 응답 한도 초과"};out.write(buffer,0,count)};out.toString("UTF-8")}.orEmpty()
            if(code !in 200..299)throw HttpFailure(code)
            return text
        } finally { c.disconnect() }
    }
}
