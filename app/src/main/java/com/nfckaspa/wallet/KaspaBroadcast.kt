package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Broadcast only after the app's card and biometric signing flow. */
internal object KaspaBroadcast {
    suspend fun postTransaction(signedJson:String):String=withContext(Dispatchers.IO) {
        parseResponse(KaspaNetwork.request("POST","/transactions",signedJson))
    }
    internal fun parseResponse(body:String):String {
        val response=JSONObject(body)
        check(response.isNull("error")||response.optString("error").isBlank()){response.optString("error")}
        return response.optString("transactionId").also { check(Regex("[0-9a-f]{64}").matches(it)){"네트워크에서 유효한 TXID를 반환하지 않았습니다"} }
    }
}
