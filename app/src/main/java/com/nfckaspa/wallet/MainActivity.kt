package com.nfckaspa.wallet

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import androidx.fragment.app.FragmentActivity
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : FragmentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var onTag: ((Tag) -> Unit)? = null
    private var onBiometricResult: ((Boolean, String) -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); nfcAdapter=NfcAdapter.getDefaultAdapter(this); setContent{WalletScreen({onTag=it},{cb->onBiometricResult=cb;authenticateBiometric()})} }
    private fun authenticateBiometric(){
        if(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){onBiometricResult?.invoke(false,"강한 생체인증을 사용할 수 없습니다");return}
        val p=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(r:BiometricPrompt.AuthenticationResult){super.onAuthenticationSucceeded(r);onBiometricResult?.invoke(true,"생체인증 성공")}
            override fun onAuthenticationError(c:Int,s:CharSequence){super.onAuthenticationError(c,s);onBiometricResult?.invoke(false,"생체인증 취소/실패: $s")}
            override fun onAuthenticationFailed(){super.onAuthenticationFailed();onBiometricResult?.invoke(false,"생체인증 실패")}
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("NFC KASPA WALLET").setSubtitle("암호화 키 사용을 승인하세요").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("취소").build())
    }
    override fun onResume(){super.onResume();val i=Intent(this,javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);val pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_MUTABLE);nfcAdapter?.enableForegroundDispatch(this,pi,null,null)}
    override fun onPause(){nfcAdapter?.disableForegroundDispatch(this);super.onPause()}
    override fun onNewIntent(i:Intent){super.onNewIntent(i);val t=if(android.os.Build.VERSION.SDK_INT>=33)i.getParcelableExtra(NfcAdapter.EXTRA_TAG,Tag::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(NfcAdapter.EXTRA_TAG);if(t!=null)onTag?.invoke(t)}
}
private enum class CardSlot(val code:Byte,val label:String){A(1,"A"),B(2,"B"),C(3,"C")}
private data class CardResult(val ok:Boolean,val slot:CardSlot?=null,val message:String,val encrypted:ByteArray?=null)
private object DeviceCrypto{
    private const val STORE="AndroidKeyStore";private const val ALIAS="nfc_kaspa_test_v03"
    private fun key():SecretKey{val ks=KeyStore.getInstance(STORE).apply{load(null)};(ks.getKey(ALIAS,null) as? SecretKey)?.let{return it};val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,STORE);kg.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setUserAuthenticationRequired(true).setUserAuthenticationParameters(30,KeyProperties.AUTH_BIOMETRIC_STRONG).build());return kg.generateKey()}
    fun encrypt(p:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return c.iv+c.doFinal(p)}
    fun decrypt(b:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));return c.doFinal(b.copyOfRange(12,b.size))}
}
private object NfcStore{
    private const val SECTOR=1;private val key=MifareClassic.KEY_DEFAULT;private val magic=byteArrayOf(0x4E,0x4B,0x57,0x33)
    fun write(t:Tag,s:CardSlot,b:ByteArray):CardResult{if(b.size>38)return CardResult(false,message="암호문 크기 오류");val m=MifareClassic.get(t)?:return CardResult(false,message="MIFARE Classic 카드가 아닙니다");return try{m.connect();if(!m.authenticateSectorWithKeyA(SECTOR,key))return CardResult(false,message="Sector 1 인증 실패");val f=m.sectorToBlock(SECTOR);val a=ByteArray(48);magic.copyInto(a);a[4]=s.code;a[5]=b.size.toByte();b.copyInto(a,6);a[47]=sum(a,47);for(x in 0..2)m.writeBlock(f+x,a.copyOfRange(x*16,(x+1)*16));val v=ByteArray(48);for(x in 0..2)m.readBlock(f+x).copyInto(v,x*16);if(v.contentEquals(a))CardResult(true,s,"CARD ${s.label} 암호화 데이터 저장 완료 ✓")else CardResult(false,message="쓰기 검증 실패")}catch(e:Exception){CardResult(false,message="NFC 오류: ${e.javaClass.simpleName}")}finally{try{m.close()}catch(_:Exception){}}}
    fun read(t:Tag):CardResult{val m=MifareClassic.get(t)?:return CardResult(false,message="MIFARE Classic 카드가 아닙니다");return try{m.connect();if(!m.authenticateSectorWithKeyA(SECTOR,key))return CardResult(false,message="Sector 1 인증 실패");val f=m.sectorToBlock(SECTOR);val a=ByteArray(48);for(x in 0..2)m.readBlock(f+x).copyInto(a,x*16);if(!a.copyOfRange(0,4).contentEquals(magic))return CardResult(false,message="v0.3 카드로 등록되지 않았습니다");if(a[47]!=sum(a,47))return CardResult(false,message="체크섬 오류");val s=CardSlot.entries.firstOrNull{it.code==a[4]}?:return CardResult(false,message="슬롯 오류");val n=a[5].toInt()and 255;if(n !in 1..38)return CardResult(false,message="암호문 길이 오류");CardResult(true,s,"CARD ${s.label} 읽기 완료",a.copyOfRange(6,6+n))}catch(e:Exception){CardResult(false,message="NFC 오류: ${e.javaClass.simpleName}")}finally{try{m.close()}catch(_:Exception){}}}
    private fun sum(d:ByteArray,n:Int):Byte{var v=0;for(i in 0 until n)v=v xor(d[i].toInt()and 255);return v.toByte()}
}
@Composable private fun WalletScreen(registerTag:(((Tag)->Unit)->Unit),requestBio:(((Boolean,String)->Unit)->Unit)){
    var status by remember{mutableStateOf("A/B/C를 선택하세요")};var selected by remember{mutableStateOf<CardSlot?>(null)};var readyBlob by remember{mutableStateOf<ByteArray?>(null)};var pendingBlob by remember{mutableStateOf<ByteArray?>(null)};var a by remember{mutableStateOf(false)};var b by remember{mutableStateOf(false)};var c by remember{mutableStateOf(false)}
    fun mark(s:CardSlot){when(s){CardSlot.A->a=true;CardSlot.B->b=true;CardSlot.C->c=true}}
    fun select(s:CardSlot){selected=s;readyBlob=null;status="CARD ${s.label}: 먼저 생체인증하세요";requestBio{ok,msg->if(ok){try{readyBlob=DeviceCrypto.encrypt(("KAS-TEST-"+s.label).toByteArray(StandardCharsets.US_ASCII));status="생체인증 완료 ✓ · 이제 CARD ${s.label}를 NFC에 다시 태그하세요"}catch(e:Exception){status="암호화 실패: ${e.javaClass.simpleName}"}}else status=msg}}
    LaunchedEffect(Unit){registerTag{tag->val s=selected;val blob=readyBlob;if(s!=null&&blob!=null){val r=NfcStore.write(tag,s,blob);status=r.message;if(r.ok){mark(s);selected=null;readyBlob=null}}else if(s!=null){status="CARD ${s.label}: NFC 태그 전에 생체인증을 완료하세요"}else{val r=NfcStore.read(tag);status=r.message;if(r.ok&&r.slot!=null&&r.encrypted!=null){mark(r.slot);pendingBlob=r.encrypted;status="CARD ${r.slot.label} 읽음 ✓ · 복호화 테스트를 누르세요"}}}}
    MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Spacer(Modifier.height(20.dp));Text("NFC KASPA WALLET",style=MaterialTheme.typography.headlineMedium);Text("TESTNET · v0.3.1 SECURITY TEST");Spacer(Modifier.height(24.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp)){Text("ENCRYPTED NFC CARD",style=MaterialTheme.typography.titleMedium);Spacer(Modifier.height(10.dp));Text("카드 선택 → Galaxy 생체인증 → NFC 태그 → 암호문 저장");Spacer(Modifier.height(14.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){Button(onClick={select(CardSlot.A)}){Text("A ${if(a)"✓" else "○"}")};Button(onClick={select(CardSlot.B)}){Text("B ${if(b)"✓" else "○"}")};Button(onClick={select(CardSlot.C)}){Text("C ${if(c)"✓" else "○"}")}};Spacer(Modifier.height(16.dp));Text(status)}};Spacer(Modifier.height(18.dp));Button(enabled=pendingBlob!=null,onClick={requestBio{ok,msg->if(ok){try{status="복호화 성공 ✓ : ${String(DeviceCrypto.decrypt(pendingBlob!!),StandardCharsets.US_ASCII)}"}catch(e:Exception){status="복호화 실패: ${e.javaClass.simpleName}"}}else status=msg}}){Text("복호화 테스트")};Spacer(Modifier.height(18.dp));Text("AES-256-GCM · Android Keystore · 생체인증 필요");Text("NFC에는 암호문만 저장됩니다");Spacer(Modifier.weight(1f));Text("실제 Seed/Private Key/KAS는 아직 사용하지 마세요")}}}
}
