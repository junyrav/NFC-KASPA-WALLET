package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Broadcast only after the app's card and biometric signing flow. */
internal object KaspaBroadcast {
    suspend fun postTransaction(signedJson:String):String=withContext(Dispatchers.IO) {
        parseResponse(KaspaNetwork.request("POST","/transactions",signedJson))
    }
    suspend fun resolveOrRetry(txid:String,request:String):String=withContext(Dispatchers.IO) {
        require(Regex("[0-9a-f]{64}").matches(txid))
        val known=runCatching{JSONObject(KaspaNetwork.request("GET","/transactions/$txid"))}.getOrNull()
        if(known?.optString("transaction_id")==txid&&known.optBoolean("is_accepted",false))return@withContext txid
        try { postTransaction(request).also{check(it==txid){"재전송 TXID 불일치"}} }
        catch(e:Exception) {
            val after=runCatching{JSONObject(KaspaNetwork.request("GET","/transactions/$txid"))}.getOrNull()
            if(after?.optString("transaction_id")==txid&&after.optBoolean("is_accepted",false))txid else throw e
        }
    }
    internal fun parseResponse(body:String):String {
        val response=JSONObject(body)
        check(response.isNull("error")||response.optString("error").isBlank()){response.optString("error")}
        return response.optString("transactionId").also { check(Regex("[0-9a-f]{64}").matches(it)){"네트워크에서 유효한 TXID를 반환하지 않았습니다"} }
    }
}
