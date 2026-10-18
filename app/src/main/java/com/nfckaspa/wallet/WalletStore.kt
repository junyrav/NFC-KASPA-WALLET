package com.nfckaspa.wallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Public metadata only. No private keys, recovery shares or passwords are persisted. */
internal object WalletStore {
    private const val PREFS = "kaspa_nfc_wallet_mainnet"
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    data class Transfer(val wallet:String,val txid:String,val to:String,val amount:String,val fee:Long,val time:Long,val direction:String="out",val state:String="submitted")
    fun loadAddress(c:Context):String = prefs(c).getString("mainnet_wallet_address","").orEmpty().takeIf { runCatching { KaspaAddress.mainnetPayload(it) }.isSuccess }.orEmpty()
    fun saveAddress(c:Context,address:String) {
        KaspaAddress.mainnetPayload(address)
        check(prefs(c).edit().putString("mainnet_wallet_address",address).commit()) { "지갑 주소를 저장하지 못했습니다" }
    }
    fun loadCards(c:Context):Set<String> = prefs(c).getStringSet("registered_cards",emptySet()).orEmpty().toSet()
    fun saveCards(c:Context,cards:Set<String>) {
        val p=prefs(c);val e=p.edit().putStringSet("registered_cards",cards)
        cards.forEach { if(!p.contains("card_date_$it"))e.putLong("card_date_$it",System.currentTimeMillis()) }
        e.apply()
    }
    fun secureCards(c:Context)=prefs(c).getStringSet("secure_cards_v2",emptySet()).orEmpty().toSet()
    fun saveSecureCards(c:Context,cards:Set<String>){check(prefs(c).edit().putStringSet("secure_cards_v2",cards).putBoolean("backup_verified_v2",false).commit())}
    fun beginProvision(c:Context){check(prefs(c).edit().putStringSet("secure_cards_v2",emptySet()).putBoolean("backup_verified_v2",false).commit())}
    fun backupVerified(c:Context)=prefs(c).getBoolean("backup_verified_v2",false)&&secureCards(c).size==3
    fun verifyBackup(c:Context){check(secureCards(c).size==3);check(prefs(c).edit().putBoolean("backup_verified_v2",true).commit())}
    data class Pending(val wallet:String,val txid:String,val request:String,val to:String,val amount:String,val fee:Long,val time:Long)
    fun savePending(c:Context,p:Pending) {
        check(pending(c)==null){"미확인 거래가 이미 있습니다"}
        val o=JSONObject().put("wallet",p.wallet).put("txid",p.txid).put("request",p.request).put("to",p.to).put("amount",p.amount).put("fee",p.fee).put("time",p.time)
        check(prefs(c).edit().putString("pending_transaction",o.toString()).commit()){"거래 복구 기록을 저장하지 못해 제출하지 않았습니다"}
    }
    fun pending(c:Context):Pending? {
        val raw=prefs(c).getString("pending_transaction",null)?:return null
        // Corruption must fail closed, never silently unlock a new payment.
        return runCatching{val o=JSONObject(raw)
            Pending(o.getString("wallet"),o.getString("txid"),o.getString("request"),o.getString("to"),o.getString("amount"),o.getLong("fee"),o.getLong("time"))
        }.getOrElse{Pending(loadAddress(c),"damaged-record","","","",0,0)}
    }
    fun clearPending(c:Context,txid:String){check(pending(c)?.txid==txid);check(prefs(c).edit().remove("pending_transaction").commit())}
    fun cardDate(c:Context,slot:String):String = prefs(c).getLong("card_date_$slot",0).takeIf{it>0}?.let{java.text.SimpleDateFormat("yyyy.MM.dd",java.util.Locale.KOREA).format(java.util.Date(it))}?:"등록 확인"
    fun currency(c:Context):String=prefs(c).getString("currency","KRW").orEmpty().takeIf{it in listOf("KRW","USD")}?:"KRW"
    fun saveCurrency(c:Context,value:String){require(value in listOf("KRW","USD"));prefs(c).edit().putString("currency",value).apply()}
    fun cardOwner(c:Context,id:String):String? = prefs(c).getString("card_uid_$id",null)
    fun bindCard(c:Context,id:String,slot:String) { prefs(c).edit().putString("card_uid_$id",slot).apply() }
    fun clearUnbacked(c:Context) { check(loadCards(c).isEmpty()); prefs(c).edit().remove("mainnet_wallet_address").apply() }
    fun history(c:Context):List<Transfer> = runCatching {
        val a=JSONArray(prefs(c).getString("transfers","[]"))
        (0 until a.length()).map { i -> val o=a.getJSONObject(i); Transfer(o.getString("wallet"),o.getString("txid"),o.getString("to"),o.getString("amount"),o.getLong("fee"),o.getLong("time"),o.optString("direction","out"),o.optString("state","submitted")) }
    }.getOrDefault(emptyList())
    fun record(c:Context,wallet:String,txid:String,to:String,amount:String,fee:Long) {
        val list=(listOf(Transfer(wallet,txid,to,amount,fee,System.currentTimeMillis()))+history(c)).distinctBy{it.wallet to it.txid}.sortedByDescending{it.time}
        saveHistory(c,list)
    }
    fun mergeHistory(c:Context,records:List<Transfer>) {
        saveHistory(c,(records+history(c)).distinctBy{it.wallet to it.txid}.sortedByDescending{it.time})
    }
    private fun saveHistory(c:Context,list:List<Transfer>) {
        val a=JSONArray()
        list.forEach { t -> a.put(JSONObject().put("wallet",t.wallet).put("txid",t.txid).put("to",t.to).put("amount",t.amount).put("fee",t.fee).put("time",t.time).put("direction",t.direction).put("state",t.state)) }
        prefs(c).edit().putString("transfers",a.toString()).apply()
    }
}
