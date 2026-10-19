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
    fun generation(c:Context)=prefs(c).getString("registration_generation_v3","").orEmpty()
    fun secureCards(c:Context)=prefs(c).getStringSet("secure_cards_v3",emptySet()).orEmpty().toSet()
    fun saveSecureCards(c:Context,cards:Set<String>){check(prefs(c).edit().putStringSet("secure_cards_v3",cards).putBoolean("recovery_verified_v3",false).putStringSet("cleaned_cards_v3",emptySet()).commit())}
    fun beginProvision(c:Context){check(prefs(c).edit().putString("registration_generation_v3",java.util.UUID.randomUUID().toString().replace("-",""))
        .putStringSet("secure_cards_v3",emptySet()).putBoolean("recovery_verified_v3",false).putStringSet("cleaned_cards_v3",emptySet()).commit())}
    fun recoveryVerified(c:Context)=prefs(c).getBoolean("recovery_verified_v3",false)&&secureCards(c).size==3
    fun cleanedCards(c:Context)=prefs(c).getStringSet("cleaned_cards_v3",emptySet()).orEmpty().toSet()
    fun backupVerified(c:Context)=recoveryVerified(c)&&cleanedCards(c).containsAll(setOf("A","B","C"))
    fun verifyBackup(c:Context){check(secureCards(c).size==3);check(prefs(c).edit().putBoolean("recovery_verified_v3",true).commit())}
    fun markClean(c:Context,slot:String){check(recoveryVerified(c));check(prefs(c).edit().putStringSet("cleaned_cards_v3",cleanedCards(c)+slot).commit())}
    data class Pending(val wallet:String,val txid:String,val request:String,val to:String,val amount:String,val fee:Long,val time:Long)
    private fun pendingFile(c:Context)=android.util.AtomicFile(java.io.File(c.filesDir,"pending-payment-v3.json"))
    private fun envelope(raw:String)=JSONObject().put("payload",raw).put("sha256",Crypto.sha(raw.toByteArray(Charsets.UTF_8)).hex()).toString()
    private fun unwrap(raw:String):String {
        val o=JSONObject(raw)
        if(!o.has("payload"))return raw // legacy record, retained during in-place upgrades
        val payload=o.getString("payload")
        require(Crypto.sha(payload.toByteArray(Charsets.UTF_8)).hex()==o.getString("sha256"))
        return payload
    }
    private fun decodePending(raw:String):Pending {val o=JSONObject(unwrap(raw));return Pending(o.getString("wallet"),o.getString("txid"),o.getString("request"),o.getString("to"),o.getString("amount"),o.getLong("fee"),o.getLong("time"))}
    fun savePending(c:Context,p:Pending) {
        check(pending(c)==null){"미확인 거래가 이미 있습니다"}
        val o=JSONObject().put("wallet",p.wallet).put("txid",p.txid).put("request",p.request).put("to",p.to).put("amount",p.amount).put("fee",p.fee).put("time",p.time)
        val raw=envelope(o.toString())
        // Both durable copies are completed before a network submission is permitted.
        val file=pendingFile(c);val out=file.startWrite()
        try{out.write(raw.toByteArray(Charsets.UTF_8));file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
        check(prefs(c).edit().putString("pending_transaction",raw).commit()){"거래 복구 기록 저장 실패 · 제출하지 않았습니다"}
    }
    fun pending(c:Context):Pending? {
        val raw=prefs(c).getString("pending_transaction",null)
        val mirror=runCatching{pendingFile(c).readFully().toString(Charsets.UTF_8)}.getOrNull()
        if(raw==null&&mirror==null&&!pendingFile(c).baseFile.exists()&&!java.io.File(c.filesDir,"pending-payment-v3.json.bak").exists())return null
        val first=raw?.let{runCatching{decodePending(it)}.getOrNull()}
        val second=mirror?.let{runCatching{decodePending(it)}.getOrNull()}
        if(first!=null&&second!=null&&first!=second)return Pending(loadAddress(c),"damaged-record","","","",0,0)
        return first?:second?:Pending(loadAddress(c),"damaged-record","","","",0,0)
    }
    fun clearPending(c:Context,txid:String){check(pending(c)?.txid==txid);pendingFile(c).delete();check(prefs(c).edit().remove("pending_transaction").commit())}
    /** Explicit abandonment never claims that a payment did not execute; archive the signed bytes and consent. */
    fun abandonPending(c:Context,accepted:Boolean,typed:String,expectedTxid:String) {
        val p=pending(c)?:error("미확인 거래 없음");val wallet=loadAddress(c)
        require(p.txid==expectedTxid&&accepted&&wallet.isNotBlank()&&typed==wallet.takeLast(8)){"주소 끝 8자와 중복 지급 위험 동의를 확인하세요"}
        val archive=JSONObject().put("txid",p.txid).put("wallet",wallet).put("request",p.request).put("to",p.to).put("amount",p.amount)
            .put("time",System.currentTimeMillis()).put("reason","explicit-user-abandonment-duplicate-payment-risk-acknowledged")
        val list=JSONArray(prefs(c).getString("abandoned_payments","[]"));list.put(archive)
        check(prefs(c).edit().putString("abandoned_payments",list.toString()).commit())
        clearPending(c,p.txid)
    }
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
