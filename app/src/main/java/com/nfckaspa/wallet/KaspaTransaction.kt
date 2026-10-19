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
        FeePolicy.rate(rate)
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
            require(mass in 1..100_000L){"소액 분할 또는 입력 개수로 네트워크 한도를 초과했습니다. 전액 입력을 선택하면 잔액 전체(수수료 제외)를 계산합니다. 부분 송금 수량은 자동으로 바꾸지 않습니다"}
            val needed=java.math.BigDecimal.valueOf(rate).multiply(java.math.BigDecimal.valueOf(mass))
                .setScale(0,java.math.RoundingMode.CEILING).longValueExact().coerceAtLeast(Math.multiplyExact(mass,FeePolicy.MIN_RATE.toLong()))
            FeePolicy.check(needed,outputs.first().amount,mass)
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

    private fun feeRate():Double = parseFeeRate(request("GET","/info/fee-estimate"))
    internal fun parseFeeRate(response:String):Double {
        val o=JSONObject(response);val a=o.optJSONArray("normalBuckets")
        val rate=if(a!=null&&a.length()>0)a.getJSONObject(0).getDouble("feerate")
            else o.getJSONObject("priorityBucket").getDouble("feerate")
        FeePolicy.rate(rate)
        return rate
    }
    // /transactions/mass expects SubmitTxModel itself, unlike /transactions which expects {transaction: ...}.
    private fun mass(tx:KaspaTx.Tx):Long {
        val local=KaspaMass.parts(tx)
        val remote=JSONObject(request("POST","/transactions/mass",txJson(tx).toString()))
        check(remote.getLong("mass")==local.total&&remote.getLong("compute_mass")==local.compute&&remote.getLong("storage_mass")==local.storage){"노드와 앱의 거래 계산이 다릅니다. 서명하지 않았습니다"}
        return local.total
    }
    fun validateIntent(p:Prepared,from:String,to:String,amount:String) {
        val recipient=KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(to))
        val owner=KaspaTx.p2pkScript(KaspaAddress.mainnetPayload(from))
        val tx=p.tx
        require(tx.version==0&&tx.outputs.size in 1..2&&tx.outputs[0].amount==kasToSompi(amount)&&tx.outputs[0].script.contentEquals(recipient)){"확인한 수신 주소 또는 금액과 실제 거래가 다릅니다"}
        require(tx.outputs.all{it.scriptVersion==0}&&tx.inputs.all{it.utxo.scriptVersion==0&&it.utxo.script.contentEquals(owner)})
        if(tx.outputs.size==2)require(tx.outputs[1].script.contentEquals(owner)){"거스름돈 주소가 다릅니다"}
        val fee=tx.inputs.fold(0L){sum,i->Math.addExact(sum,i.utxo.amount)}-tx.outputs.fold(0L){sum,o->Math.addExact(sum,o.amount)}
        require(fee==p.fee){"확인한 수수료와 실제 거래가 다릅니다"}
        val mass=KaspaMass.calculate(tx);require(mass<=100_000)
        FeePolicy.check(fee,tx.outputs[0].amount,mass)
        require(fee>=Math.multiplyExact(mass,FeePolicy.MIN_RATE.toLong())){"수수료가 네트워크 최소값보다 작습니다"}
    }


    private fun txJson(tx:KaspaTx.Tx)=JSONObject().put("version",tx.version)
        .put("inputs",JSONArray().also{a->tx.inputs.forEach{i->a.put(JSONObject().put("previousOutpoint",JSONObject().put("transactionId",toHex(i.utxo.outpoint.transactionId)).put("index",i.utxo.outpoint.index)).put("signatureScript",toHex(i.signatureScript)).put("sequence",i.sequence).put("sigOpCount",i.sigOpCount))}})
        .put("outputs",JSONArray().also{a->tx.outputs.forEach{o->a.put(JSONObject().put("amount",o.amount).put("scriptPublicKey",JSONObject().put("version",o.scriptVersion).put("scriptPublicKey",toHex(o.script))))}})
        .put("lockTime",tx.lockTime).put("subnetworkId","0000000000000000000000000000000000000000")

    private fun request(method:String,path:String,body:String?=null)=KaspaNetwork.request(method,path,body)
    internal fun kasToSompi(t:String):Long { val s=t.trim();require(Regex("""\d+(\.\d{1,8})?""").matches(s)){"수량 형식 오류"};val p=s.split('.');val w=p[0].toLong();val f=if(p.size==2)p[1].padEnd(8,'0').toLong() else 0L;return Math.addExact(Math.multiplyExact(w,SOMPI),f).also { require(it>0) {"송금 수량은 0보다 커야 합니다"} } }
    private fun fromHex(s:String):ByteArray {require(s.length%2==0&&Regex("[0-9a-fA-F]*").matches(s));return ByteArray(s.length/2){i->s.substring(i*2,i*2+2).toInt(16).toByte()}}
    private fun toHex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}
}
