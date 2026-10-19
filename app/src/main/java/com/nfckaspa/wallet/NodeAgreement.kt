package com.nfckaspa.wallet

import org.json.JSONArray
import org.json.JSONObject

internal data class NodeEndpoint(val uid:String,val url:String) {val host get()=java.net.URI(url).host}
internal data class NetworkWitness(val address:String,val digest:String,val nodes:List<NodeEndpoint>,val verifiedAt:Long,val feeRate:Double)
internal data class AgreedUtxos(val utxos:List<KaspaTx.Utxo>,val witness:NetworkWitness)

internal object NodeAgreement {
    const val MAX_UTXOS=500
    const val MAX_AGE=30_000L
    fun number(o:JSONObject,key:String):Long {val n=o.get(key);require(n is Number&&n !is Double&&n !is Float){"노드 정수 형식 오류: $key"};return n.toLong().also{require(it>=0)}}
    fun bool(o:JSONObject,key:String):Boolean {val value=o.get(key);require(value is Boolean);return value}
    fun hex(s:String,size:Int?=null):ByteArray {require(s.length%2==0&&s.matches(Regex("[0-9a-f]*"))&&(size==null||s.length==size*2));return ByteArray(s.length/2){s.substring(it*2,it*2+2).toInt(16).toByte()}}
    fun endpoint(uid:String,url:String):NodeEndpoint {
        val u=java.net.URI(url)
        require(uid.isNotBlank()&&uid.length<=128&&u.scheme=="wss"&&u.userInfo==null&&u.query==null&&u.fragment==null&&u.port in listOf(-1,443))
        require(u.host?.matches(Regex("[a-z0-9-]+\\.kaspa\\.(stream|red|green|blue)"))==true)
        require(u.path=="/kaspa/mainnet/wrpc/json")
        return NodeEndpoint(uid,url)
    }
    fun serverInfo(json:JSONObject):Long {
        require(json.getString("networkId")=="mainnet"&&bool(json,"isSynced")&&bool(json,"hasUtxoIndex")){"대조 노드가 메인넷 동기화/UTXO 색인 조건을 충족하지 못했습니다"}
        return number(json,"virtualDaaScore")
    }
    fun parseUtxos(address:String,array:JSONArray,rpc:Boolean):List<KaspaTx.Utxo> {
        require(array.length()<=MAX_UTXOS){"UTXO가 너무 많습니다. 이 버전의 단일 거래 한도를 확인하세요"}
        val owner=KaspaTx.p2pkScript(KaspaAddress.recipientPayload(address))
        val list=(0 until array.length()).map{n->
            val row=array.getJSONObject(n)
            if(row.has("address")&&!row.isNull("address"))require(row.getString("address")==address)
            val op=row.getJSONObject("outpoint");val e=row.getJSONObject("utxoEntry")
            val (version,script)=if(rpc){val raw=e.getString("scriptPublicKey");require(raw.length>=4);raw.take(4).toInt(16) to hex(raw.drop(4))}
                else{val spk=e.getJSONObject("scriptPublicKey");number(spk,"version").also{require(it<=65535)}.toInt() to hex(spk.getString("scriptPublicKey"))}
            val amount=number(e,"amount");val index=number(op,"index");require(index<=0xffffffffL&&amount in 1..2_870_400_000_000_000_000L)
            require(version==0&&script.contentEquals(owner)){"다른 지갑의 UTXO 응답입니다"}
            KaspaTx.Utxo(KaspaTx.Outpoint(hex(op.getString("transactionId"),32),index),amount,version,script,number(e,"blockDaaScore"),bool(e,"isCoinbase"))
        }
        require(list.map{it.outpoint.transactionId.hex()+":"+it.outpoint.index}.toSet().size==list.size){"중복 UTXO 응답"}
        return list
    }
    fun digest(utxos:List<KaspaTx.Utxo>):String {
        val rows=utxos.map{"${it.outpoint.transactionId.hex()}:${it.outpoint.index}:${it.amount}:${it.scriptVersion}:${it.script.hex()}:${it.blockDaaScore}:${it.isCoinbase}"}.sorted()
        require(rows.distinct().size==rows.size)
        return Crypto.sha(rows.joinToString("\n").toByteArray(Charsets.UTF_8)).hex()
    }
    fun agree(address:String,rest:List<KaspaTx.Utxo>,nodes:List<NodeEndpoint>,snapshots:List<List<KaspaTx.Utxo>>,daa:List<Long>,rates:List<Double>,now:Long):AgreedUtxos {
        require(nodes.size==2&&snapshots.size==2&&daa.size==2&&rates.size==3)
        require(nodes.map{it.host}.toSet().size==2&&nodes.map{it.uid}.toSet().size==2){"서로 다른 대조 노드 2곳이 필요합니다"}
        require(daa.all{it>=0}&&kotlin.math.abs(daa[0]-daa[1])<=1200){"대조 노드의 동기화 시점이 다릅니다"}
        val fingerprint=digest(rest)
        check(snapshots.all{digest(it)==fingerprint}){"REST와 독립 노드의 UTXO 정보가 다릅니다. 서명하지 않았습니다. 잔액 갱신 후 다시 확인하세요"}
        rates.forEach{FeePolicy.rate(it)}
        return AgreedUtxos(rest,NetworkWitness(address,fingerprint,nodes,now,rates.sorted()[1]))
    }
    fun requireFresh(witness:NetworkWitness?,address:String,tx:KaspaTx.Tx,now:Long) {
        requireNotNull(witness){"독립 노드 대조 기록이 없습니다. 수수료를 다시 계산하세요"}
        require(witness.address==address&&now-witness.verifiedAt in 0..MAX_AGE&&witness.nodes.size==2&&witness.nodes.map{it.host}.toSet().size==2&&witness.nodes.map{it.uid}.toSet().size==2){"노드 대조가 만료되었습니다. 다시 확인하세요"}
        check(digest(tx.inputs.map{it.utxo})==witness.digest){"대조한 입력과 실제 서명 입력이 다릅니다"}
    }
    fun acceptedInChain(response:JSONObject,txid:String,acceptingHash:String):Boolean {
        hex(txid,32);hex(acceptingHash,32)
        val removed=response.getJSONArray("removedChainBlockHashes")
        if((0 until removed.length()).any{removed.getString(it)==acceptingHash})return false
        val added=response.getJSONArray("addedChainBlockHashes")
        if((0 until added.length()).none{added.getString(it)==acceptingHash})return false
        val entries=response.getJSONArray("acceptedTransactionIds")
        return (0 until entries.length()).any{n->val e=entries.getJSONObject(n);val ids=e.getJSONArray("acceptedTransactionIds")
            e.getString("acceptingBlockHash")==acceptingHash&&(0 until ids.length()).any{ids.getString(it)==txid}}
    }
}
