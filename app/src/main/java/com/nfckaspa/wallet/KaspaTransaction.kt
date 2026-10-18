package com.nfckaspa.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Mainnet version-0 P2PK transaction preparation. */
internal object KaspaTransaction {
    private const val SOMPI = 100_000_000L
    data class Prepared(val tx: KaspaTx.Tx, val fee: Long)

    suspend fun prepare(from:String,to:String,amountText:String):Prepared=withContext(Dispatchers.IO) {
        KaspaAddress.mainnetPayload(from);KaspaAddress.mainnetPayload(to)
        build(from,to,kasToSompi(amountText),fetchUtxos(from),feeRate(),::mass)
    }

    suspend fun prepareMaximum(from:String,to:String):Prepared=withContext(Dispatchers.IO) {
        KaspaAddress.mainnetPayload(from);KaspaAddress.mainnetPayload(to)
        build(from,to,null,fetchUtxos(from),feeRate(),::mass)
    }

    /** Re-price the actual outputs after subtracting the fee: KIP-9 storage mass depends on change. */
    internal fun build(from:String,to:String,amount:Long?,utxos:List<KaspaTx.Utxo>,rate:Double,massOf:(KaspaTx.Tx)->Long):Prepared {
        val owner=KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(from))
        val recipient=KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(to))
        require(amount==null||amount>0){"송금 수량은 0보다 커야 합니다"}
        require(rate.isFinite()&&rate>0){"수수료율 오류"}
        require(utxos.isNotEmpty()){ "사용 가능한 UTXO가 없습니다" }
        utxos.forEach { u->
            require(u.amount>0&&u.scriptVersion==0&&u.script.contentEquals(owner)){"현재 지갑의 UTXO가 아닙니다"}
            require(u.outpoint.transactionId.size==32&&u.outpoint.index in 0..0xffffffffL){"UTXO 식별자 오류"}
        }
        require(utxos.map{toHex(it.outpoint.transactionId) to it.outpoint.index}.distinct().size==utxos.size){"중복 UTXO 응답"}
        val total=utxos.fold(0L){sum,u->Math.addExact(sum,u.amount)}
        var fee=1L
        repeat(16) {
            require(total>fee && (amount==null || amount<total-fee)){"수수료를 포함한 잔액이 부족합니다. 전액 전송은 전액 입력을 사용하세요"}
            val outputs=if(amount==null)listOf(KaspaTx.Output(total-fee,script=recipient))
                else listOf(KaspaTx.Output(amount,script=recipient),KaspaTx.Output(total-amount-fee,script=owner))
            val provisional=KaspaTx.Tx(inputs=utxos.map{KaspaTx.Input(it,signatureScript=ByteArray(66))},outputs=outputs)
            val mass=massOf(provisional)
            require(mass in 1..100_000L){"단일 거래의 네트워크 mass 한도를 초과했습니다"}
            val needed=java.math.BigDecimal.valueOf(rate).multiply(java.math.BigDecimal.valueOf(mass))
                .setScale(0,java.math.RoundingMode.CEILING).longValueExact().coerceAtLeast(mass)
            if(fee>=needed)return Prepared(KaspaTx.Tx(inputs=utxos.map{KaspaTx.Input(it)},outputs=outputs),fee)
            fee=needed
        }
        error("수수료 계산이 수렴하지 않았습니다. 다시 시도하세요")
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

    private fun request(method:String,path:String,body:String?=null)=KaspaNetwork.request(method,path,body)
    internal fun kasToSompi(t:String):Long { val s=t.trim();require(Regex("""\d+(\.\d{1,8})?""").matches(s)){"수량 형식 오류"};val p=s.split('.');val w=p[0].toLong();val f=if(p.size==2)p[1].padEnd(8,'0').toLong() else 0L;return Math.addExact(Math.multiplyExact(w,SOMPI),f).also { require(it>0) {"송금 수량은 0보다 커야 합니다"} } }
    private fun fromHex(s:String)=ByteArray(s.length/2){i->s.substring(i*2,i*2+2).toInt(16).toByte()}
    private fun toHex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}
}
