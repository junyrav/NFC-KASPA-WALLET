package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

/** TN10-only transaction preparation. */
internal object Tn10Transaction {
    private const val BASE = "https://api-tn10.kaspa.org"
    private const val SOMPI = 100_000_000L
    data class Prepared(val tx: KaspaTx.Tx, val fee: Long)

    suspend fun prepare(from: String, to: String, amountText: String): Prepared = withContext(Dispatchers.IO) {
        require(from.startsWith("kaspatest:") && to.startsWith("kaspatest:")) { "TN10 주소만 사용할 수 있습니다" }
        val amount = kasToSompi(amountText)
        val recipient = KaspaTx.p2pkScript(KaspaAddress.testnetPayload(to))
        val changeSpk = KaspaTx.p2pkScript(KaspaAddress.testnetPayload(from))
        val utxos = fetchUtxos(from)
        require(utxos.isNotEmpty()) { "사용 가능한 UTXO가 없습니다" }
        val total = utxos.sumOf { it.amount }
        require(total > amount) { "수수료를 포함한 잔액이 부족합니다" }
        val provisional = KaspaTx.Tx(inputs = utxos.map { KaspaTx.Input(it, signatureScript = ByteArray(66)) }, outputs = listOf(KaspaTx.Output(amount, script = recipient), KaspaTx.Output(total - amount, script = changeSpk)))
        val mass = mass(provisional)
        val fee = ceil(mass * feeRate()).toLong().coerceAtLeast(mass)
        val change = total - amount - fee
        require(change > 0) { "수수료를 포함한 잔액이 부족합니다" }
        Prepared(KaspaTx.Tx(inputs = utxos.map { KaspaTx.Input(it) }, outputs = listOf(KaspaTx.Output(amount, script = recipient), KaspaTx.Output(change, script = changeSpk))), fee)
    }

    fun signedRequest(tx: KaspaTx.Tx): String = JSONObject().put("transaction", txJson(tx)).put("allowOrphan", false).toString()

    private fun fetchUtxos(address: String): List<KaspaTx.Utxo> {
        val a = JSONArray(request("GET", "/addresses/$address/utxos"))
        return (0 until a.length()).map { i ->
            val o=a.getJSONObject(i); val op=o.getJSONObject("outpoint"); val e=o.getJSONObject("utxoEntry"); val spk=e.getJSONObject("scriptPublicKey")
            KaspaTx.Utxo(KaspaTx.Outpoint(fromHex(op.getString("transactionId")),op.getLong("index")),e.getLong("amount"),spk.optInt("version",0),fromHex(spk.getString("scriptPublicKey")))
        }
    }

    private fun feeRate():Double { val o=JSONObject(request("GET","/info/fee-estimate")); val a=o.optJSONArray("normalBuckets"); if(a!=null&&a.length()>0)return a.getJSONObject(0).getDouble("feerate"); return o.optJSONObject("priorityBucket")?.optDouble("feerate",1.0)?:1.0 }
    // /transactions/mass expects SubmitTxModel itself, unlike /transactions which expects {transaction: ...}.
    private fun mass(tx:KaspaTx.Tx):Long { val o=JSONObject(request("POST","/transactions/mass",txJson(tx).toString())); return o.optLong("mass").takeIf{it>0}?:throw IllegalStateException("mass 계산 실패: $o") }

    private fun txJson(tx:KaspaTx.Tx)=JSONObject().put("version",tx.version)
        .put("inputs",JSONArray().also{a->tx.inputs.forEach{i->a.put(JSONObject().put("previousOutpoint",JSONObject().put("transactionId",toHex(i.utxo.outpoint.transactionId)).put("index",i.utxo.outpoint.index)).put("signatureScript",toHex(i.signatureScript)).put("sequence",i.sequence).put("sigOpCount",i.sigOpCount))}})
        .put("outputs",JSONArray().also{a->tx.outputs.forEach{o->a.put(JSONObject().put("amount",o.amount).put("scriptPublicKey",JSONObject().put("version",o.scriptVersion).put("scriptPublicKey",toHex(o.script))))}})
        .put("lockTime",tx.lockTime).put("subnetworkId","0000000000000000000000000000000000000000")

    private fun request(method:String,path:String,body:String?=null):String { require(BASE.contains("api-tn10")); val c=URL(BASE+path).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=20000;c.requestMethod=method;c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","NFC-KASPA-WALLET/0.6.9-TN10");if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toByteArray())}};return try{val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();if(code !in 200..299)throw IllegalStateException("TN10 HTTP $code ${text.take(500)}");text}finally{c.disconnect()} }
    private fun kasToSompi(t:String):Long { val s=t.trim();require(Regex("\\d+(\\.\\d{1,8})?").matches(s)){"수량 형식 오류"};val p=s.split('.');val w=p[0].toLong();val f=if(p.size==2)p[1].padEnd(8,'0').toLong() else 0L;return Math.addExact(Math.multiplyExact(w,SOMPI),f) }
    private fun fromHex(s:String)=ByteArray(s.length/2){i->s.substring(i*2,i*2+2).toInt(16).toByte()}
    private fun toHex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}
}
