package com.nfckaspa.wallet

import java.io.File
import java.security.SecureRandom

/** No software signer fallback. Host extraction is restricted to debuggable unit-test builds. */
internal object NativeSecp {
    private val initialization by lazy {
        runCatching {
            val host=if(BuildConfig.DEBUG)System.getProperty("kaspa.native.testLibrary") else null
            if(host==null)System.loadLibrary("kaspa_secp") else {
                val copy=File.createTempFile("kaspa-secp-test-",".so").apply{deleteOnExit()}
                File(host).inputStream().use{input->copy.outputStream().use{input.copyTo(it)}}
                System.load(copy.absolutePath)
            }
            val seed=ByteArray(32).also{SecureRandom().nextBytes(it)}
            try{check(nativeInitialize(seed))}finally{seed.fill(0)}
        }
    }
    private fun ready(){initialization.getOrElse{throw IllegalStateException("네이티브 서명 엔진을 사용할 수 없습니다. 서명하지 않았습니다",it)}}
    fun validKey(key:ByteArray):Boolean {ready();return key.size==32&&nativeValidKey(key)}
    fun publicKey(key:ByteArray):ByteArray {ready();require(key.size==32);return nativePublicKey(key).also{check(it.size==32)}}
    fun validPublicKey(key:ByteArray):Boolean {ready();return key.size==32&&nativeValidPublicKey(key)}
    fun sign(message:ByteArray,key:ByteArray,aux:ByteArray):ByteArray {ready();require(message.size==32&&key.size==32&&aux.size==32);return nativeSign(message,key,aux).also{check(it.size==64)}}
    fun verify(message:ByteArray,key:ByteArray,sig:ByteArray):Boolean {ready();return message.size==32&&key.size==32&&sig.size==64&&nativeVerify(message,key,sig)}
    private external fun nativeInitialize(seed:ByteArray):Boolean
    private external fun nativeValidKey(key:ByteArray):Boolean
    private external fun nativePublicKey(key:ByteArray):ByteArray
    private external fun nativeValidPublicKey(key:ByteArray):Boolean
    private external fun nativeSign(message:ByteArray,key:ByteArray,aux:ByteArray):ByteArray
    private external fun nativeVerify(message:ByteArray,key:ByteArray,signature:ByteArray):Boolean
}
