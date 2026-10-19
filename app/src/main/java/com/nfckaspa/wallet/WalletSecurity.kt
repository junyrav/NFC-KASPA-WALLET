package com.nfckaspa.wallet

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

internal object PasswordPolicy {
    fun valid(p:String):Boolean = p.length>=16 && p.toSet().size>=8 &&
        listOf(p.any{it.isLowerCase()},p.any{it.isUpperCase()},p.any{it.isDigit()},p.any{!it.isLetterOrDigit()}).count{it}>=3 &&
        listOf("password","qwerty","123456","비밀번호").none{p.lowercase().contains(it)}
    fun generate():String {while(true){val candidate=android.util.Base64.encodeToString(Crypto.random(18),android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE);if(valid(candidate))return candidate}}
    fun requireValid(p:String) { require(valid(p)){"새 복구 비밀번호는 16자 이상, 영문 대·소문자·숫자·기호 중 3종류를 사용하세요"} }
}

internal object Crypto {
    private const val ALIAS="nkw_mainnet_per_operation_v2"
    private val rng=SecureRandom()
    fun random(n:Int)=ByteArray(n).also{rng.nextBytes(it)}
    private fun dev():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply{init(keySpec())}.generateKey()
    }
    internal fun keySpec():KeyGenParameterSpec {
        val spec=KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
        else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1)
        return spec.build()
    }

    fun encryptCipher():Cipher {
        try{return Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,dev())}}
        catch(e:android.security.keystore.KeyPermanentlyInvalidatedException) {
            // Called only after a new key is generated or two cards recovered it.
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(ALIAS)}
            return Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,dev())}
        }
    }
    fun decryptCipher(rec:CardRecord):Cipher {
        require(rec.version>=2){"기존 카드는 카드 2장으로 복구 후 보안 카드로 재등록하세요"}
        require(rec.daily.size==60)
        return Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,dev(),GCMParameterSpec(128,rec.daily.copyOfRange(0,12)))}
    }
    fun finishDailyEncrypt(cipher:Cipher,key:ByteArray,header:ByteArray):ByteArray {
        cipher.updateAAD(header);return cipher.iv+cipher.doFinal(key)
    }
    fun finishDailyDecrypt(cipher:Cipher,rec:CardRecord):ByteArray {
        cipher.updateAAD(rec.header());return cipher.doFinal(rec.daily.copyOfRange(12,rec.daily.size))
    }
    private fun argonKey(pass:CharArray,salt:ByteArray):SecretKey {
        require(salt.size==16)
        val params=Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(1).withSalt(salt).build()
        val bytes=ByteArray(32)
        try { Argon2BytesGenerator().apply{init(params)}.generateBytes(pass,bytes);return SecretKeySpec(bytes,"AES") }
        finally { bytes.fill(0);params.clear() }
    }
    fun passEnc(plain:ByteArray,pass:CharArray,aad:ByteArray):ByteArray {
        val salt=random(16);val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,argonKey(pass,salt));cipher.updateAAD(aad)
        return salt+cipher.iv+cipher.doFinal(plain)
    }
    fun passDec(rec:CardRecord,pass:CharArray):ByteArray {
        val b=rec.recovery;require(b.size==77){"복구 데이터 길이 오류"}
        val key=if(rec.version>=2)argonKey(pass,b.copyOfRange(0,16)) else {
            val spec=PBEKeySpec(pass,b.copyOfRange(0,16),120000,256)
            val bytes=try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}
            try{SecretKeySpec(bytes,"AES")}finally{bytes.fill(0)}
        }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,b.copyOfRange(16,28)))
        if(rec.version>=2)cipher.updateAAD(rec.header())
        return cipher.doFinal(b.copyOfRange(28,b.size))
    }
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
}

internal object RecoveryIdentity {
    fun validate(key:ByteArray,expectedAddress:String,authenticatedPub:ByteArray?) {
        require(expectedAddress.isNotBlank()||authenticatedPub!=null){"지갑 식별 정보 또는 기존 주소가 필요합니다"}
        val pub=KaspaAddress.xOnlyPublicKey(key)
        require(authenticatedPub==null||java.security.MessageDigest.isEqual(pub,authenticatedPub)){"복원한 키와 카드의 지갑 식별자가 다릅니다"}
        if(expectedAddress.isNotBlank())require(KaspaAddress.mainnet(key)==expectedAddress){"복구 주소가 다릅니다. 카드 조합을 확인하세요"}
    }
}
