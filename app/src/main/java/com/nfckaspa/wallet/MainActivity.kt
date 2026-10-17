package com.nfckaspa.wallet

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var onTag: ((Tag) -> Unit)? = null
    private var onBiometricResult: ((Boolean, String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent { WalletScreen(this, { onTag = it }, { callback -> onBiometricResult = callback; authenticate() }) }
    }

    private fun authenticate() {
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            onBiometricResult?.invoke(false, "강한 생체인증을 사용할 수 없습니다")
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onBiometricResult?.invoke(true, "생체인증 성공")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onBiometricResult?.invoke(false, "생체인증 취소/실패: $errString")
            }
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onBiometricResult?.invoke(false, "생체인증 실패")
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("NFC KASPA WALLET")
            .setSubtitle("NFC 암호화 키 사용을 승인하세요")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("취소")
            .build())
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pi, null, null)
    }

    override fun onPause() {
        nfcAdapter?.disableForegroundDispatch(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) onTag?.invoke(tag)
    }
}

private enum class CardSlot(val code: Byte, val label: String) { A(1,"A"), B(2,"B"), C(3,"C") }
private data class CardResult(val ok:Boolean, val slot:CardSlot?=null, val message:String, val encrypted:ByteArray?=null)

private object DeviceCrypto {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "nfc_kaspa_test_v03"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .build())
        return kg.generateKey()
    }

    fun encrypt(plain:ByteArray):ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key())
        return c.iv + c.doFinal(plain)
    }
    fun decrypt(blob:ByteArray):ByteArray {
        require(blob.size>12)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,blob.copyOfRange(0,12)))
        return c.doFinal(blob.copyOfRange(12,blob.size))
    }
}

private object NfcStore {
    private const val SECTOR=1
    private val key=MifareClassic.KEY_DEFAULT
    private val magic=byteArrayOf(0x4E,0x4B,0x57,0x33) // NKW3

    fun write(tag:Tag,slot:CardSlot,blob:ByteArray):CardResult {
        if(blob.size>38) return CardResult(false,message="암호문이 NFC 테스트 영역보다 큽니다")
        val mc=MifareClassic.get(tag)?:return CardResult(false,message="MIFARE Classic 카드가 아닙니다")
        return try {
            mc.connect(); if(!mc.authenticateSectorWithKeyA(SECTOR,key)) return CardResult(false,message="Sector 1 인증 실패")
            val first=mc.sectorToBlock(SECTOR)
            val all=ByteArray(48)
            magic.copyInto(all,0); all[4]=slot.code; all[5]=blob.size.toByte(); blob.copyInto(all,6)
            all[47]=checksum(all,47)
            for(i in 0..2) mc.writeBlock(first+i,all.copyOfRange(i*16,(i+1)*16))
            val verify=ByteArray(48); for(i in 0..2) mc.readBlock(first+i).copyInto(verify,i*16)
            if(!verify.contentEquals(all)) CardResult(false,message="쓰기 후 검증 실패") else CardResult(true,slot,"CARD ${slot.label} 암호화 데이터 저장 완료 ✓")
        } catch(e:Exception){ CardResult(false,message="NFC 오류: ${e.javaClass.simpleName}") }
        finally { try{mc.close()}catch(_:Exception){} }
    }

    fun read(tag:Tag):CardResult {
        val mc=MifareClassic.get(tag)?:return CardResult(false,message="MIFARE Classic 카드가 아닙니다")
        return try {
            mc.connect(); if(!mc.authenticateSectorWithKeyA(SECTOR,key)) return CardResult(false,message="Sector 1 인증 실패")
            val first=mc.sectorToBlock(SECTOR); val all=ByteArray(48)
            for(i in 0..2) mc.readBlock(first+i).copyInto(all,i*16)
            if(!all.copyOfRange(0,4).contentEquals(magic)) return CardResult(false,message="v0.3 카드로 다시 등록해주세요")
            if(all[47]!=checksum(all,47)) return CardResult(false,message="카드 데이터 체크섬 오류")
            val slot=CardSlot.entries.firstOrNull{it.code==all[4]}?:return CardResult(false,message="카드 슬롯 오류")
            val len=all[5].toInt() and 0xFF
            if(len !in 1..38) return CardResult(false,message="암호문 길이 오류")
            CardResult(true,slot,"CARD ${slot.label} 암호문 읽기 완료",all.copyOfRange(6,6+len))
        } catch(e:Exception){ CardResult(false,message="NFC 오류: ${e.javaClass.simpleName}") }
        finally { try{mc.close()}catch(_:Exception){} }
    }
    private fun checksum(d:ByteArray,n:Int):Byte { var v=0; for(i in 0 until n)v=v xor(d[i].toInt() and 255); return v.toByte() }
}

@Composable
private fun WalletScreen(activity:MainActivity, registerTag:(((Tag)->Unit)->Unit), requestBio:(((Boolean,String)->Unit)->Unit)) {
    var status by remember{mutableStateOf("먼저 A/B/C 중 하나를 선택해 암호화 테스트 데이터를 등록하세요")}
    var pending by remember{mutableStateOf<CardSlot?>(null)}
    var lastTag by remember{mutableStateOf<Tag?>(null)}
    var pendingBlob by remember{mutableStateOf<ByteArray?>(null)}
    var a by remember{mutableStateOf(false)}; var b by remember{mutableStateOf(false)}; var c by remember{mutableStateOf(false)}
    fun mark(s:CardSlot){when(s){CardSlot.A->a=true;CardSlot.B->b=true;CardSlot.C->c=true}}

    LaunchedEffect(Unit){
        registerTag{tag->
            lastTag=tag
            val s=pending
            if(s!=null){
                status="CARD ${s.label}: 생체인증이 필요합니다"
                requestBio{ok,msg->
                    if(ok){
                        try{
                            val test=("KAS-TEST-"+s.label).toByteArray(StandardCharsets.US_ASCII)
                            val enc=DeviceCrypto.encrypt(test)
                            val r=NfcStore.write(tag,s,enc); status=r.message; if(r.ok){mark(s);pending=null}
                        }catch(e:Exception){status="암호화 실패: ${e.javaClass.simpleName}"}
                    }else status=msg
                }
            }else{
                val r=NfcStore.read(tag); status=r.message
                if(r.ok&&r.slot!=null&&r.encrypted!=null){
                    mark(r.slot); pendingBlob=r.encrypted
                    status="CARD ${r.slot.label} 읽음 · [복호화 테스트]를 누르세요"
                }
            }
        }
    }

    MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Spacer(Modifier.height(20.dp)); Text("NFC KASPA WALLET",style=MaterialTheme.typography.headlineMedium); Text("TESTNET · v0.3 SECURITY TEST")
        Spacer(Modifier.height(24.dp)); Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp)){
            Text("ENCRYPTED NFC CARD",style=MaterialTheme.typography.titleMedium); Spacer(Modifier.height(10.dp))
            Text("카드를 선택 → NFC 태그 → Galaxy 생체인증 → 암호화 데이터 저장")
            Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                Button(onClick={pending=CardSlot.A;status="CARD A를 태그하세요"}){Text("A ${if(a)"✓" else "○"}")}
                Button(onClick={pending=CardSlot.B;status="CARD B를 태그하세요"}){Text("B ${if(b)"✓" else "○"}")}
                Button(onClick={pending=CardSlot.C;status="CARD C를 태그하세요"}){Text("C ${if(c)"✓" else "○"}")}
            }; Spacer(Modifier.height(16.dp)); Text(status)
        }}
        Spacer(Modifier.height(18.dp)); Button(enabled=pendingBlob!=null,onClick={
            requestBio{ok,msg-> if(ok){try{val p=DeviceCrypto.decrypt(pendingBlob!!);status="복호화 성공 ✓ : ${String(p,StandardCharsets.US_ASCII)}"}catch(e:Exception){status="복호화 실패: ${e.javaClass.simpleName}"}}else status=msg}
        }){Text("복호화 테스트")}
        Spacer(Modifier.height(18.dp)); Text("AES-256-GCM · Android Keystore · 생체인증 필요"); Text("NFC에는 암호문만 저장됩니다")
        Spacer(Modifier.weight(1f)); Text("실제 Seed/Private Key/KAS는 아직 사용하지 마세요")
    }}}
}
