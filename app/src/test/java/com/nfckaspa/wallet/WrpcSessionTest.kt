package com.nfckaspa.wallet

import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WrpcSessionTest {
    private fun withServer(wrongId:Boolean,action:(WrpcSession)->Unit) {
        val server=MockWebServer();val client=OkHttpClient()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onMessage(webSocket:WebSocket,text:String){val q=JSONObject(text)
                webSocket.send(JSONObject().put("id",q.getLong("id")+(if(wrongId)1 else 0)).put("method",q.getString("method")).put("params",JSONObject().put("marker",7)).toString())}
        }))
        server.start()
        try{WrpcSession(server.url("/").toString().replaceFirst("http:","ws:"),client).use(action)}
        finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();server.shutdown()}
    }
    @Test fun jsonWrpcReadsParamsAndNeverAllowsTransactionSubmission()=withServer(false){rpc->
        assertEquals(7,rpc.call("getServerInfo").getInt("marker"))
        assertThrows(IllegalArgumentException::class.java){rpc.call("submitTransaction")}
    }
    @Test fun aResponseForAnotherRequestIsRejected()=withServer(true){rpc->
        assertThrows(IllegalStateException::class.java){rpc.call("getServerInfo")}
    }
}
