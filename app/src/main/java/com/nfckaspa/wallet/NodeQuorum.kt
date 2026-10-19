package com.nfckaspa.wallet

import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** REST plus two distinct PNN node identities. Endpoints are community services, not consensus proofs. */
internal object NodeQuorum {
    private val resolvers=listOf("eric.kaspa.stream","maxim.kaspa.stream","troy.kaspa.stream","john.kaspa.red","mike.kaspa.red","alex.kaspa.red","mark.kaspa.green","jack.kaspa.blue","luke.kaspa.blue")
    internal fun resolve():List<NodeEndpoint> {
        val found=mutableListOf<NodeEndpoint>()
        for(host in resolvers.shuffled().take(6)) {
            val endpoint=runCatching{val o=JSONObject(KaspaNetwork.resolver("https://$host/v2/kaspa/mainnet/tls/wrpc/borsh"))
                val raw=o.getString("url");require(raw.endsWith("/wrpc/borsh"));NodeAgreement.endpoint(o.getString("uid"),raw.removeSuffix("borsh")+"json")}.getOrNull()?:continue
            if(found.none{it.host==endpoint.host||it.uid==endpoint.uid})found+=endpoint
            if(found.size==2)return found
        }
        error("서로 다른 공개 노드 2곳에 연결할 수 없습니다. 서명하지 않았습니다. 잠시 후 다시 시도하세요")
    }
    suspend fun verified(address:String,pinned:List<NodeEndpoint>?=null):AgreedUtxos=withContext(Dispatchers.IO){coroutineScope{
        KaspaAddress.recipientPayload(address)
        val rest=async{NodeAgreement.parseUtxos(address,JSONArray(KaspaNetwork.request("GET","/addresses/$address/utxos")),false)}
        val rate=async{KaspaTransaction.parseFeeRate(KaspaNetwork.request("GET","/info/fee-estimate"))}
        val nodes=pinned?:resolve()
        require(nodes.size==2);nodes.forEach{NodeAgreement.endpoint(it.uid,it.url)}
        val snapshots=nodes.map{node->async{WrpcSession(node.url).use{rpc->
            val daa=NodeAgreement.serverInfo(rpc.call("getServerInfo"))
            val utxos=NodeAgreement.parseUtxos(address,rpc.call("getUtxosByAddresses",JSONObject().put("addresses",JSONArray().put(address))).getJSONArray("entries"),true)
            val fee=KaspaTransaction.parseFeeRate(rpc.call("getFeeEstimate").getJSONObject("estimate").toString())
            Triple(utxos,daa,fee)
        }}}.awaitAll()
        ensureActive()
        NodeAgreement.agree(address,rest.await(),nodes,snapshots.map{it.first},snapshots.map{it.second},listOf(rate.await())+snapshots.map{it.third},SystemClock.elapsedRealtime())
    }}
    /** Positive acceptance on both nodes is mandatory. A missing or unavailable proof keeps the payment lock. */
    suspend fun confirms(txid:String,acceptingHash:String,originalRequest:String?=null,containingHash:String?=null):Boolean=withContext(Dispatchers.IO){coroutineScope{
        NodeAgreement.hex(txid,32);NodeAgreement.hex(acceptingHash,32)
        resolve().map{node->async{runCatching{WrpcSession(node.url).use{rpc->
            NodeAgreement.serverInfo(rpc.call("getServerInfo"))
            if(originalRequest!=null){
                val hash=requireNotNull(containingHash);NodeAgreement.hex(hash,32)
                val containing=rpc.call("getBlock",JSONObject().put("hash",hash).put("includeTransactions",true)).getJSONObject("block")
                require(RpcTransactionProof.provesConflict(containing,hash,txid,originalRequest)){"경쟁 거래 입력 증명을 확인할 수 없습니다"}
            }
            val block=rpc.call("getBlock",JSONObject().put("hash",acceptingHash).put("includeTransactions",false)).getJSONObject("block")
            val verbose=block.getJSONObject("verboseData")
            require(verbose.getString("hash")==acceptingHash&&NodeAgreement.bool(verbose,"isChainBlock"))
            val parent=verbose.getString("selectedParentHash");NodeAgreement.hex(parent,32)
            val chain=rpc.call("getVirtualChainFromBlock",JSONObject().put("startHash",parent).put("includeAcceptedTransactionIds",true).put("minConfirmationCount",10))
            NodeAgreement.acceptedInChain(chain,txid,acceptingHash)
        }}.getOrDefault(false)}}.awaitAll().all{it}
    }}
}
