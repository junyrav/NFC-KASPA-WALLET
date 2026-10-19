package com.nfckaspa.wallet

import org.json.JSONObject

/** Read-only native-v0 TXID decoding for conflict evidence. Unsupported variants never unlock a payment. */
internal object RpcTransactionProof {
    fun decode(o:JSONObject):KaspaTx.Tx {
        require(NodeAgreement.number(o,"version")==0L&&o.getString("subnetworkId")=="0".repeat(40))
        require(NodeAgreement.number(o,"gas")==0L&&o.getString("payload").isEmpty())
        val ins=o.getJSONArray("inputs");val outs=o.getJSONArray("outputs")
        require(ins.length() in 1..500&&outs.length() in 1..500)
        return KaspaTx.Tx(inputs=(0 until ins.length()).map{n->val i=ins.getJSONObject(n);val p=i.getJSONObject("previousOutpoint");val index=NodeAgreement.number(p,"index");require(index<=0xffffffffL)
            KaspaTx.Input(KaspaTx.Utxo(KaspaTx.Outpoint(NodeAgreement.hex(p.getString("transactionId"),32),index),1,0,byteArrayOf()),NodeAgreement.number(i,"sequence"))},
            outputs=(0 until outs.length()).map{n->val q=outs.getJSONObject(n);val script=q.getString("scriptPublicKey");require(script.length in 4..20004)
                KaspaTx.Output(NodeAgreement.number(q,"value"),script.take(4).toInt(16),NodeAgreement.hex(script.drop(4)))},lockTime=NodeAgreement.number(o,"lockTime"))
    }
    fun provesConflict(block:JSONObject,containingHash:String,txid:String,originalRequest:String):Boolean {
        require(block.getJSONObject("verboseData").getString("hash")==containingHash)
        val rows=block.getJSONArray("transactions");require(rows.length()<=2000)
        val original=KaspaBroadcast.requestOutpoints(originalRequest)
        return (0 until rows.length()).any{n->runCatching{
            val tx=decode(rows.getJSONObject(n))
            KaspaTx.transactionId(tx)==txid&&tx.inputs.any{"${it.utxo.outpoint.transactionId.hex()}:${it.utxo.outpoint.index}" in original}
        }.getOrDefault(false)}
    }
}
