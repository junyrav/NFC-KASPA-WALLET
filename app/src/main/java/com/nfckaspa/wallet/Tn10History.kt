package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/** Public TN10 indexer data; never requires a card or a signing key. */
internal object Tn10History {
    const val PAGE_SIZE = 50
    data class Page(val records:List<WalletStore.Transfer>,val count:Int)
    suspend fun fetch(address:String,offset:Int=0):Page = withContext(Dispatchers.IO) {
        KaspaAddress.testnetPayload(address)
        require(offset>=0)
        val connection=URL("https://api-tn10.kaspa.org/addresses/$address/full-transactions?limit=$PAGE_SIZE&offset=$offset&resolve_previous_outpoints=full").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=15000;connection.readTimeout=20000
            connection.setRequestProperty("Accept","application/json")
            check(connection.responseCode==200){"TN10 HTTP ${connection.responseCode}"}
            parse(address,connection.inputStream.bufferedReader().use{it.readText()})
        } finally { connection.disconnect() }
    }
    fun parse(address:String,json:String):Page {
        val array=JSONArray(json)
        val records=(0 until array.length()).map { index ->
            val tx=array.getJSONObject(index)
            val inputs=(tx.optJSONArray("inputs")?:JSONArray()).objects()
            val outputs=tx.getJSONArray("outputs").objects()
            val resolved=inputs.map { input ->
                val previous=input.optJSONObject("previous_outpoint_resolved")
                val owner=previous?.optString("script_public_key_address")?.takeUnless{it=="null"||it.isBlank()}
                    ?:input.optString("previous_outpoint_address").takeUnless{it=="null"||it.isBlank()}
                val amount=previous?.takeUnless{it.isNull("amount")}?.getLong("amount")
                    ?:input.takeUnless{it.isNull("previous_outpoint_amount")}?.getLong("previous_outpoint_amount")
                owner to amount
            }
            fun sum(values:List<Long>)=values.fold(0L){total,n->require(n>=0);Math.addExact(total,n)}
            val received=sum(outputs.filter{it.optString("script_public_key_address")==address}.map{it.getLong("amount")})
            val spent=sum(resolved.filter{it.first==address}.map{requireNotNull(it.second){"입력 금액 확인 실패"}})
            val known=resolved.all{it.first!=null && it.second!=null}
            val direction=when { spent>0 && received>=spent->"in";spent>0 && outputs.all{it.optString("script_public_key_address")==address}->"self";spent>0->"out";known||inputs.isEmpty()->"in";else->"unknown" }
            val totalOut=sum(outputs.map{it.getLong("amount")})
            val fee=if(resolved.all{it.second!=null} && inputs.isNotEmpty()) Math.subtractExact(sum(resolved.map{it.second!!}),totalOut).also{require(it>=0)} else -1L
            // Net wallet change, excluding our fee for an ordinary outgoing transfer.
            val amount=when(direction){"out"->(spent-received-(if(known && resolved.all{it.first==address})fee.coerceAtLeast(0) else 0L)).coerceAtLeast(0);"self"->received;else->received-spent}
            val counterparty=when(direction){"in"->resolved.mapNotNull{it.first}.filter{it!=address}.distinct().joinToString(", ");"out"->outputs.map{it.optString("script_public_key_address")}.filter{it!=address&&it!="null"&&it.isNotBlank()}.distinct().joinToString(", ");else->address}
            WalletStore.Transfer(address,tx.getString("transaction_id"),counterparty,BigDecimal.valueOf(amount,8).toPlainString(),fee,tx.optLong("block_time",0),direction,if(tx.isNull("is_accepted"))"unknown" else if(tx.getBoolean("is_accepted"))"accepted" else "unaccepted")
        }
        return Page(records,array.length())
    }
    private fun JSONArray.objects()=(0 until length()).map{getJSONObject(it)}
}
