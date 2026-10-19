package com.nfckaspa.wallet

import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Read-only JSON-wRPC. Exact id/method binding; no transaction-submission method exists here. */
internal class WrpcSession(url:String,client:OkHttpClient=CLIENT):java.io.Closeable {
    private data class Pending(val id:Long,val method:String,val result:CompletableFuture<JSONObject>)
    @Volatile private var pending:Pending?=null
    private val opened=CompletableFuture<Unit>()
    private var nextId=0L
    private val ws:WebSocket=client.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
        override fun onOpen(webSocket:WebSocket,response:Response){opened.complete(Unit)}
        override fun onMessage(webSocket:WebSocket,text:String){
            val p=pending?:return
            try{check(text.length<=KaspaNetwork.MAX_BODY){"대조 응답이 너무 큽니다"};val o=JSONObject(text)
                require(NodeAgreement.number(o,"id")==p.id&&o.getString("method")==p.method){"대조 응답 식별자 불일치"}
                check(o.isNull("error")){"대조 노드 요청 실패"};p.result.complete(o.getJSONObject("params"))
            }catch(e:Exception){p.result.completeExceptionally(e);webSocket.cancel()}
        }
        override fun onMessage(webSocket:WebSocket,bytes:ByteString){pending?.result?.completeExceptionally(IllegalStateException("JSON 대조 응답이 아닙니다"));webSocket.cancel()}
        override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){opened.completeExceptionally(t);pending?.result?.completeExceptionally(t)}
        override fun onClosed(webSocket:WebSocket,code:Int,reason:String){pending?.result?.completeExceptionally(IllegalStateException("대조 연결 종료"))}
    })
    init {try{opened.get(5,TimeUnit.SECONDS)}catch(e:Exception){ws.cancel();throw IllegalStateException("대조 노드 연결 실패 · 다시 시도하세요",e)}}
    @Synchronized fun call(method:String,params:JSONObject=JSONObject()):JSONObject {
        require(method in setOf("getServerInfo","getUtxosByAddresses","getFeeEstimate","getBlock","getVirtualChainFromBlock"))
        val p=Pending(++nextId,method,CompletableFuture());pending=p
        try{check(ws.send(JSONObject().put("id",p.id).put("method",method).put("params",params).toString()));return p.result.get(8,TimeUnit.SECONDS)}
        catch(e:Exception){ws.cancel();throw IllegalStateException("대조 노드 응답 확인 실패 · 서명하지 않았습니다",e)}
        finally{pending=null}
    }
    override fun close(){ws.cancel()}
    companion object {
        private val CLIENT=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(5,TimeUnit.SECONDS)
            .dns(object:Dns {
                override fun lookup(hostname:String):List<java.net.InetAddress> {
                    val addresses=Dns.SYSTEM.lookup(hostname)
                    require(addresses.isNotEmpty()&&addresses.none {
                        it.isAnyLocalAddress||it.isLoopbackAddress||it.isLinkLocalAddress||
                            it.isSiteLocalAddress||it.isMulticastAddress||
                            (it.address.size==16&&(it.address[0].toInt() and 0xfe)==0xfc)
                    }) { "공개 노드 주소가 아닙니다" }
                    return addresses
                }
            }).build()
    }
}
