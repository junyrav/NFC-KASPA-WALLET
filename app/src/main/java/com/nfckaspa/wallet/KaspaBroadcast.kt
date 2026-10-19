package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Broadcast only after the app's card and biometric signing flow. */
internal object KaspaBroadcast {
    suspend fun postTransaction(signedJson:String):String=withContext(Dispatchers.IO) {
        check(!BuildConfig.DEBUG){"디버그 빌드는 메인넷 송금을 제출하지 않습니다"}
        parseResponse(KaspaNetwork.request("POST","/transactions",signedJson))
    }
    enum class Resolution{ACCEPTED,CONFLICTED,UNKNOWN}
    internal fun requestOutpoints(request:String):Set<String> {
        val tx=JSONObject(request).getJSONObject("transaction");require(tx.getInt("version")==0)
        val inputs=tx.getJSONArray("inputs");require(inputs.length()>0)
        return (0 until inputs.length()).map{val p=inputs.getJSONObject(it).getJSONObject("previousOutpoint")
            val id=p.getString("transactionId");require(id.matches(Regex("[0-9a-f]{64}")));val index=p.getLong("index");require(index in 0..0xffffffffL);"$id:$index"}.toSet().also{require(it.size==inputs.length())}
    }
    internal fun evidence(txid:String,request:String,historyJson:String):Resolution {
        val original=requestOutpoints(request);val rows=org.json.JSONArray(historyJson)
        for(i in 0 until rows.length()) {
            val tx=rows.getJSONObject(i);if(!tx.optBoolean("is_accepted",false))continue
            val id=tx.optString("transaction_id");if(!id.matches(Regex("[0-9a-f]{64}")))continue
            if(id==txid)return Resolution.ACCEPTED
            val inputs=tx.optJSONArray("inputs")?:continue
            for(j in 0 until inputs.length()) {
                val input=inputs.getJSONObject(j);val hash=input.optString("previous_outpoint_hash")
                if(input.has("previous_outpoint_index")&&"$hash:${input.getLong("previous_outpoint_index")}" in original)return Resolution.CONFLICTED
            }
        }
        return Resolution.UNKNOWN
    }
    internal fun validateStoredRequest(txid:String,request:String) {
        fun bytes(hex:String):ByteArray{require(hex.length%2==0&&hex.matches(Regex("[0-9a-f]*")));return ByteArray(hex.length/2){hex.substring(it*2,it*2+2).toInt(16).toByte()}}
        val o=JSONObject(request).getJSONObject("transaction");require(o.getInt("version")==0&&o.getString("subnetworkId")=="0".repeat(40)&&o.optLong("gas",0)==0L&&o.optString("payload","").isBlank())
        val inputs=o.getJSONArray("inputs");val outputs=o.getJSONArray("outputs");require(inputs.length()>0&&outputs.length() in 1..2)
        val tx=KaspaTx.Tx(0,(0 until inputs.length()).map{val i=inputs.getJSONObject(it);val p=i.getJSONObject("previousOutpoint");val signature=bytes(i.getString("signatureScript"));require(signature.size==66)
            KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(bytes(p.getString("transactionId")),p.getLong("index")),1,0,byteArrayOf()),i.getLong("sequence"),signature,i.getInt("sigOpCount"))},
            (0 until outputs.length()).map{val q=outputs.getJSONObject(it);val script=q.getJSONObject("scriptPublicKey");KaspaTx.Output(q.getLong("amount"),script.getInt("version"),bytes(script.getString("scriptPublicKey")))},o.getLong("lockTime"))
        requestOutpoints(request)
        require(tx.outputs.all{it.amount>0&&it.scriptVersion==0&&it.script.size==34&&it.script[0]==0x20.toByte()&&it.script[33]==0xac.toByte()&&KaspaAddress.isValidXOnlyPublicKey(it.script.copyOfRange(1,33))})
        require(KaspaTx.transactionId(tx)==txid){"저장 거래와 TXID가 일치하지 않아 재전송하지 않았습니다"}
    }
    suspend fun reconcile(wallet:String,txid:String,request:String):Resolution=withContext(Dispatchers.IO) {
        require(txid.matches(Regex("[0-9a-f]{64}")));KaspaAddress.mainnetPayload(wallet);requestOutpoints(request)
        val known=runCatching{JSONObject(KaspaNetwork.request("GET","/transactions/$txid"))}.getOrNull()
        if(known?.optString("transaction_id")==txid&&known.optBoolean("is_accepted",false))return@withContext Resolution.ACCEPTED
        // Absence/unspent inputs never prove rejection. Only positive accepted conflict evidence releases the lock.
        for(offset in 0 until 500 step 50) {
            val body=KaspaNetwork.request("GET","/addresses/$wallet/full-transactions?limit=50&offset=$offset&resolve_previous_outpoints=no")
            val result=evidence(txid,request,body);if(result!=Resolution.UNKNOWN)return@withContext result
            if(org.json.JSONArray(body).length()<50)break
        }
        Resolution.UNKNOWN
    }
    suspend fun resolveOrRetry(wallet:String,txid:String,request:String):Resolution=withContext(Dispatchers.IO) {
        validateStoredRequest(txid,request)
        var result=reconcile(wallet,txid,request)
        if(result!=Resolution.UNKNOWN)return@withContext result
        try{check(postTransaction(request)==txid){"재전송 TXID 불일치"}}catch(_:Exception){/* Reconcile; status codes alone never unlock a new payment. */}
        result=reconcile(wallet,txid,request)
        result
    }
    internal fun parseResponse(body:String):String {
        val response=JSONObject(body)
        check(response.isNull("error")||response.optString("error").isBlank()){response.optString("error")}
        return response.optString("transactionId").also { check(Regex("[0-9a-f]{64}").matches(it)){"네트워크에서 유효한 TXID를 반환하지 않았습니다"} }
    }
}
