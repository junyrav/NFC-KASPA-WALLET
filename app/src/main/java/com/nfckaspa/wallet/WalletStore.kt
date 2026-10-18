package com.nfckaspa.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Public metadata only. No private keys, recovery shares or passwords are persisted. */
internal object WalletStore {
    private const val PREFS = "kaspa_nfc_wallet"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    data class Transfer(val wallet:String,val txid:String,val to:String,val amount:String,val fee:Long,val time:Long)
    fun loadAddress(c:Context):String = prefs(c).getString("tn10_wallet_address","").orEmpty().takeIf { runCatching { KaspaAddress.testnetPayload(it) }.isSuccess }.orEmpty()
    fun saveAddress(c:Context,address:String) {
        KaspaAddress.testnetPayload(address)
        check(prefs(c).edit().putString("tn10_wallet_address",address).commit()) { "지갑 주소를 저장하지 못했습니다" }
    }
    fun loadCards(c:Context):Set<String> = prefs(c).getStringSet("registered_cards",emptySet()).orEmpty().toSet()
    fun saveCards(c:Context,cards:Set<String>) { prefs(c).edit().putStringSet("registered_cards",cards).apply() }
    fun history(c:Context):List<Transfer> = runCatching {
        val a=JSONArray(prefs(c).getString("transfers","[]"))
        (0 until a.length()).map { i -> val o=a.getJSONObject(i); Transfer(o.getString("wallet"),o.getString("txid"),o.getString("to"),o.getString("amount"),o.getLong("fee"),o.getLong("time")) }
    }.getOrDefault(emptyList())
    fun record(c:Context,wallet:String,txid:String,to:String,amount:String,fee:Long) {
        val list=(listOf(Transfer(wallet,txid,to,amount,fee,System.currentTimeMillis()))+history(c)).distinctBy{it.txid}.take(200)
        val a=JSONArray()
        list.forEach { t -> a.put(JSONObject().put("wallet",t.wallet).put("txid",t.txid).put("to",t.to).put("amount",t.amount).put("fee",t.fee).put("time",t.time)) }
        prefs(c).edit().putString("transfers",a.toString()).apply()
    }
}
