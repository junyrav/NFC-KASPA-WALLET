package com.nfckaspa.wallet

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : FragmentActivity() {
    private var nfc: NfcAdapter? = null
    private var onTag: ((Tag) -> Unit)? = null
    private var bio: ((Boolean, String) -> Unit)? = null
    override fun onCreate(b: Bundle?) { super.onCreate(b); nfc=NfcAdapter.getDefaultAdapter(this); setContent{KaspaWalletTheme{App({onTag=it},{cb->bio=cb;auth()})}} }
    private fun auth(){
        if(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){bio?.invoke(false,"강한 생체인증을 사용할 수 없습니다");return}
        val p=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(r:BiometricPrompt.AuthenticationResult){bio?.invoke(true,"생체인증 성공")}
            override fun onAuthenticationError(c:Int,s:CharSequence){bio?.invoke(false,"생체인증 취소/실패: $s")}
            override fun onAuthenticationFailed(){bio?.invoke(false,"생체인증 실패")}
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Kaspa NFC Wallet").setSubtitle("송금 보안 작업을 승인하세요").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("취소").build())
    }
    override fun onResume(){super.onResume();val i=Intent(this,javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);nfc?.enableForegroundDispatch(this,PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_MUTABLE),null,null)}
    override fun onPause(){nfc?.disableForegroundDispatch(this);super.onPause()}
    override fun onNewIntent(i:Intent){super.onNewIntent(i);val t=if(android.os.Build.VERSION.SDK_INT>=33)i.getParcelableExtra(NfcAdapter.EXTRA_TAG,Tag::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(NfcAdapter.EXTRA_TAG);if(t!=null)onTag?.invoke(t)}
}

private enum class Slot(val n:Int){A(1),B(2),C(3)}
private data class Rec(val slot:Slot,val daily:ByteArray,val recovery:ByteArray)
private sealed class ReadResult{data class Ok(val rec:Rec):ReadResult();data class Error(val msg:String):ReadResult()}

private object Crypto{
    private val rng=SecureRandom();private const val KS="AndroidKeyStore";private const val ALIAS="nkw_v05_daily"
    private fun dev():SecretKey{val s=KeyStore.getInstance(KS).apply{load(null)};(s.getKey(ALIAS,null) as? SecretKey)?.let{return it};val g=KeyGenerator.getInstance("AES",KS);g.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setUserAuthenticationRequired(true).setUserAuthenticationParameters(30,KeyProperties.AUTH_BIOMETRIC_STRONG).build());return g.generateKey()}
    fun random(n:Int)=ByteArray(n).also{rng.nextBytes(it)}
    fun dailyEnc(p:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,dev());return c.iv+c.doFinal(p)}
    fun dailyDec(b:ByteArray):ByteArray{require(b.size>=60){"daily payload length ${b.size}"};val iv=b.copyOfRange(0,12);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,dev(),GCMParameterSpec(128,iv));return c.doFinal(b.copyOfRange(12,b.size))}
    private fun passKey(pass:String,salt:ByteArray):SecretKey{val f=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");return SecretKeySpec(f.generateSecret(PBEKeySpec(pass.toCharArray(),salt,120000,256)).encoded,"AES")}
    fun passEnc(p:ByteArray,pass:String):ByteArray{val salt=random(16);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,passKey(pass,salt));return salt+c.iv+c.doFinal(p)}
    fun passDec(b:ByteArray,pass:String):ByteArray{val salt=b.copyOfRange(0,16);val iv=b.copyOfRange(16,28);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,passKey(pass,salt),GCMParameterSpec(128,iv));return c.doFinal(b.copyOfRange(28,b.size))}
}

private object Shares{
    private fun mul(a:Int,b:Int):Int{var x=a;var y=b;var r=0;repeat(8){if((y and 1)!=0)r=r xor x;val hi=x and 0x80;x=(x shl 1) and 255;if(hi!=0)x=x xor 0x1b;y=y shr 1};return r}
    private fun pow(a:Int,n:Int):Int{var r=1;var x=a;var e=n;while(e>0){if((e and 1)!=0)r=mul(r,x);x=mul(x,x);e=e shr 1};return r}
    private fun div(a:Int,b:Int)=mul(a,pow(b,254))
    fun split(secret:ByteArray):Map<Slot,ByteArray>{val slope=Crypto.random(secret.size);return Slot.entries.associateWith{s->ByteArray(secret.size+1).also{out->out[0]=s.n.toByte();for(i in secret.indices)out[i+1]=(secret[i].toInt() xor mul(slope[i].toInt() and 255,s.n)).toByte()}}}
    fun join(a:ByteArray,b:ByteArray):ByteArray{val x1=a[0].toInt() and 255;val x2=b[0].toInt() and 255;require(x1!=x2);return ByteArray(a.size-1){i->val y1=a[i+1].toInt() and 255;val y2=b[i+1].toInt() and 255;val m=div(y1 xor y2,x1 xor x2);(y1 xor mul(m,x1)).toByte()}}
}

private object Card{
    private val key=MifareClassic.KEY_DEFAULT;private val magic=byteArrayOf(0x4e,0x4b,0x57,0x35);private val sectors=1..5;private const val MAX=5
    private fun auth(m:MifareClassic,sec:Int):Boolean{repeat(MAX){if(runCatching{m.authenticateSectorWithKeyA(sec,key)}.getOrDefault(false))return true;Thread.sleep(60)};return false}
    fun write(t:Tag,s:Slot,d:ByteArray,r:ByteArray,progress:(String)->Unit):String{val payload=ByteArray(240);magic.copyInto(payload);payload[4]=s.n.toByte();payload[5]=d.size.toByte();payload[6]=r.size.toByte();d.copyInto(payload,7);r.copyInto(payload,7+d.size);payload[239]=sum(payload,239);var last="";for(attempt in 0 until MAX){progress("CARD ${s.name} 쓰는 중... (${attempt+1}/$MAX)");val m=MifareClassic.get(t)?:return "MIFARE Classic 카드가 아닙니다";try{m.connect();var off=0;var ok=true;for(sec in sectors){if(!auth(m,sec)){last="Sector $sec 인증 실패";ok=false;break};val first=m.sectorToBlock(sec);for(j in 0..2){m.writeBlock(first+j,payload.copyOfRange(off,off+16));off+=16}};if(ok)return "CARD ${s.name} 저장 완료 ✓"}catch(e:Exception){last="${e.javaClass.simpleName}: ${e.message?:""}"}finally{runCatching{m.close()}};Thread.sleep(80)};return "CARD ${s.name} 쓰기 실패 ✕ (5/5) · 카드를 다시 태그하세요 · $last"}
    fun read(t:Tag,progress:(String)->Unit):ReadResult{var last="";for(attempt in 0 until MAX){progress("카드 읽는 중... (${attempt+1}/$MAX)");val m=MifareClassic.get(t)?:return ReadResult.Error("읽기 실패: MIFARE Classic 카드가 아님");try{m.connect();val p=ByteArray(240);var off=0;var ok=true;for(sec in sectors){if(!auth(m,sec)){last="Sector $sec Key A 인증 실패";ok=false;break};val first=m.sectorToBlock(sec);for(j in 0..2){m.readBlock(first+j).copyInto(p,off);off+=16}};if(ok){if(!p.copyOfRange(0,4).contentEquals(magic))return ReadResult.Error("읽기 실패: 등록된 카드 포맷이 아닙니다");if(p[239]!=sum(p,239))return ReadResult.Error("읽기 실패: Checksum 불일치");val s=Slot.entries.firstOrNull{it.n==(p[4].toInt() and 255)}?:return ReadResult.Error("읽기 실패: Slot 값 오류");val dl=p[5].toInt() and 255;val rl=p[6].toInt() and 255;if(dl<1||rl<1||7+dl+rl>239)return ReadResult.Error("읽기 실패: 데이터 길이 오류");return ReadResult.Ok(Rec(s,p.copyOfRange(7,7+dl),p.copyOfRange(7+dl,7+dl+rl)))}}catch(e:Exception){last="${e.javaClass.simpleName}: ${e.message?:""}"}finally{runCatching{m.close()}};Thread.sleep(80)};return ReadResult.Error("카드 읽기 실패 ✕ (5/5) · 카드를 다시 태그하세요 · $last")}
    private fun sum(d:ByteArray,n:Int):Byte{var v=0;for(i in 0 until n)v=v xor(d[i].toInt() and 255);return v.toByte()}
}

private object Tn10{
    private suspend fun get(path:String):String=withContext(Dispatchers.IO){val c=(URL("https://api-tn10.kaspa.org$path").openConnection() as HttpURLConnection).apply{connectTimeout=10000;readTimeout=10000;requestMethod="GET";setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Kaspa-NFC-Wallet/${BuildConfig.VERSION_NAME}")};try{val code=c.responseCode;val stream=if(code in 200..299)c.inputStream else c.errorStream;val body=stream?.bufferedReader()?.use{it.readText()}.orEmpty();if(code !in 200..299)throw IllegalStateException("HTTP $code ${body.take(160)}");body}finally{c.disconnect()}}
    suspend fun balance(address:String):String{val body=get("/addresses/$address/balance");val n=Regex("\"balance\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()?:throw IllegalStateException("balance 필드 없음: ${body.take(120)}");return "%.8f KAS".format(n/100000000.0)}
}

private fun qr(text:String):Bitmap{val m=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,640,640);return Bitmap.createBitmap(640,640,Bitmap.Config.RGB_565).also{b->for(x in 0 until 640)for(y in 0 until 640)b.setPixel(x,y,if(m[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}

@Composable private fun App(reg:(((Tag)->Unit)->Unit),bio:(((Boolean,String)->Unit)->Unit)){
    var status by remember{mutableStateOf("Recovery Password를 정하고 Testnet 지갑을 생성하세요")};var pass by remember{mutableStateOf("")};var secret by remember{mutableStateOf<ByteArray?>(null)};var address by remember{mutableStateOf("")}
    var shares by remember{mutableStateOf<Map<Slot,ByteArray>>(emptyMap())};var daily by remember{mutableStateOf<ByteArray?>(null)};var target by remember{mutableStateOf<Slot?>(null)};var found by remember{mutableStateOf<Map<Slot,ByteArray>>(emptyMap())};var balance by remember{mutableStateOf("-- KAS")};var showReceive by remember{mutableStateOf(false)};var showSend by remember{mutableStateOf(false)};var showCards by remember{mutableStateOf(false)};var showSettings by remember{mutableStateOf(false)}
    var sendTo by remember{mutableStateOf("")};var sendAmount by remember{mutableStateOf("")};var sendInfo by remember{mutableStateOf("")};var sendReady by remember{mutableStateOf(false)};var awaitingSendCard by remember{mutableStateOf(false)};var prepared by remember{mutableStateOf<Tn10Transaction.Prepared?>(null)}
    val scope=rememberCoroutineScope();val context=LocalContext.current
    fun copyAddress(){if(address.isBlank())return;val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("Kaspa TN10 address",address));status="지갑 주소 복사 완료 ✓"}
    fun refresh(){if(address.isBlank())return;balance="조회 중...";scope.launch{try{balance=Tn10.balance(address);status="TN10 잔액 조회 성공 ✓"}catch(e:Exception){balance="조회 실패";status="TN10 조회 오류 · ${e.javaClass.simpleName}: ${e.message?:"원인 불명"}"}}}
    fun prepare(s:Slot){if(secret==null||pass.length<8){status="먼저 Testnet 지갑을 생성/복구하세요";return};bio{ok,msg->if(ok){try{daily=Crypto.dailyEnc(secret!!);target=s;status="생체인증 완료 ✓ · CARD ${s.name}를 태그하세요"}catch(e:Exception){status="암호화 실패: ${e.javaClass.simpleName}"}}else status=msg}}
    fun preflight(){sendReady=false;prepared=null;if(address.isBlank()){sendInfo="먼저 Testnet 지갑을 생성/복구하세요";return};if(!sendTo.startsWith("kaspatest:")||sendTo.length<20){sendInfo="수신 주소를 확인하세요";return};val a=sendAmount.toDoubleOrNull();if(a==null||a<=0.0){sendInfo="송금 수량을 확인하세요";return};sendInfo="TN10 UTXO / mass / 수수료 계산 중...";scope.launch{try{val p=Tn10Transaction.prepare(address,sendTo,sendAmount);prepared=p;sendReady=true;sendInfo="송금 준비 완료 · fee ${"%.8f".format(p.fee/100000000.0)} KAS";status="사전검증 완료 ✓ · 송금 인증을 누르세요"}catch(e:Exception){prepared=null;sendReady=false;sendInfo="송금 사전검증 실패 · ${e.message?:e.javaClass.simpleName}";status="송금 준비 실패 ✕"}}}
    fun beginSend(){if(!sendReady||prepared==null){sendInfo="먼저 UTXO / 수수료 확인을 완료하세요";return};awaitingSendCard=true;status="송금 인증 1/2 · 등록된 NFC 카드 1장을 태그하세요";sendInfo="NFC 카드 대기 중..."}

    LaunchedEffect(Unit){reg{tag->if(awaitingSendCard){when(val rr=Card.read(tag){status=it}){is ReadResult.Error->{status=rr.msg;sendInfo="NFC 인증 실패"};is ReadResult.Ok->{awaitingSendCard=false;sendInfo="CARD ${rr.rec.slot.name} 확인 ✓ · 생체인증 대기";status="송금 인증 2/2 · Galaxy 생체인증을 해주세요";bio{ok,msg->if(ok){var key:ByteArray?=null;try{key=Crypto.dailyDec(rr.rec.daily);if(KaspaAddress.testnet(key)==address){val p=prepared?:throw IllegalStateException("거래 준비 정보가 없습니다");sendInfo="NFC + 생체인증 완료 ✓ · 거래 서명 중...";status="TN10 거래 서명 중...";KaspaTx.signAll(p.tx,key);val request=Tn10Transaction.signedRequest(p.tx);key.fill(0);key=null;sendReady=false;scope.launch{try{status="TN10 Broadcast 중...";sendInfo="서명 완료 ✓ · 네트워크 제출 중...";val txid=Tn10Broadcast.postTransaction(request);sendInfo="송금 성공 ✓ · TXID $txid";status="TN10 Broadcast 성공 ✓ · TXID $txid";prepared=null;refresh()}catch(e:Exception){sendInfo="Broadcast 실패 ✕ · ${e.message?:e.javaClass.simpleName}";status="TN10 송금 실패 · 거래가 승인되지 않았습니다";prepared=null}}}else{sendInfo="카드의 지갑 키가 현재 주소와 일치하지 않습니다";status="송금 인증 실패 ✕"}}catch(e:Exception){sendInfo="daily key/서명 사용 불가 · ${e.message?:e.javaClass.simpleName}";status="송금 인증 또는 서명 실패 ✕ · 필요 시 CARD ${rr.rec.slot.name} 재등록"}finally{key?.fill(0)}}else{sendInfo=msg;status="송금 생체인증 실패/취소"}}}}}else{val t=target;if(t!=null&&daily!=null){val rec=Crypto.passEnc(shares[t]!!,pass);status=Card.write(tag,t,daily!!,rec){status=it};if(status.contains("저장 완료")){target=null;daily=null;status="CARD ${t.name} 갱신 완료 ✓ · 현재 Android Keystore daily key 적용"}}else when(val rr=Card.read(tag){status=it}){is ReadResult.Error->status=rr.msg;is ReadResult.Ok->try{val r=rr.rec;val sh=Crypto.passDec(r.recovery,pass);found=found+(r.slot to sh);status="CARD ${r.slot.name} 읽기 완료 ✓ · Share 확인 (${found.size}/2)";if(found.size>=2){val two=found.values.take(2);val recovered=Shares.join(two[0],two[1]);address=KaspaAddress.testnet(recovered);secret=recovered;shares=Shares.split(recovered);status="2-of-3 복구 성공 ✓ · 동일 Testnet 주소 재생성";refresh()}}catch(e:Exception){status="복호화 실패: 비밀번호 오류 또는 데이터 손상"}}}}}

    Surface(Modifier.fillMaxSize(),color=KaspaSurface){Column(Modifier.padding(horizontal=20.dp,vertical=16.dp).verticalScroll(rememberScrollState())){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column{Text("Kaspa NFC Wallet",fontSize=24.sp,fontWeight=FontWeight.Bold,color=KaspaInk);Text("Secure NFC wallet",fontSize=12.sp,color=KaspaTealDark)};Spacer(Modifier.weight(1f));Surface(color=KaspaTeal,shape=RoundedCornerShape(50)){Text("TN10",Modifier.padding(horizontal=14.dp,vertical=7.dp),color=Color.White,fontWeight=FontWeight.Bold)}}
        Spacer(Modifier.height(18.dp));Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=KaspaMint),shape=RoundedCornerShape(26.dp)){Column(Modifier.padding(22.dp)){Text("TOTAL BALANCE",fontSize=12.sp,color=KaspaTealDark,fontWeight=FontWeight.SemiBold);Spacer(Modifier.height(4.dp));Text(balance,fontSize=34.sp,fontWeight=FontWeight.Bold,color=KaspaInk);Text("≈ ₩ --",fontSize=18.sp,color=KaspaTealDark);Spacer(Modifier.height(14.dp));Row(Modifier.fillMaxWidth()){Text("1 KAS = ₩ --",fontSize=13.sp,color=KaspaTealDark);Spacer(Modifier.weight(1f));Text("-- · 24h",fontSize=13.sp,color=KaspaTealDark)}}}
        Spacer(Modifier.height(16.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick={showSend=!showSend;showReceive=false;showCards=false;showSettings=false},enabled=address.isNotEmpty(),modifier=Modifier.weight(1f).height(56.dp),shape=RoundedCornerShape(18.dp)){Text("Send",fontWeight=FontWeight.Bold)};OutlinedButton(onClick={showReceive=!showReceive;showSend=false;showCards=false;showSettings=false},enabled=address.isNotEmpty(),modifier=Modifier.weight(1f).height(56.dp),shape=RoundedCornerShape(18.dp)){Text("Receive",fontWeight=FontWeight.Bold)}}
        Spacer(Modifier.height(16.dp));Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){Text("Wallet address",fontSize=12.sp,color=KaspaTealDark);Spacer(Modifier.height(5.dp));Text(shortKaspaAddress(address),fontWeight=FontWeight.SemiBold,color=KaspaInk);if(address.isNotEmpty()){Spacer(Modifier.height(10.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={copyAddress()},modifier=Modifier.weight(1f)){Text("Copy")};OutlinedButton(onClick={refresh()},modifier=Modifier.weight(1f)){Text("Refresh")}}}}}
        if(address.isEmpty()){Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(16.dp)){Text("지갑 시작하기",fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));OutlinedTextField(pass,{pass=it;found=emptyMap()},label={Text("Recovery Password (8자 이상)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));Button(onClick={if(pass.length<8)status="비밀번호는 8자 이상으로 해주세요" else{secret=KaspaAddress.newPrivateKey();address=KaspaAddress.testnet(secret!!);shares=Shares.split(secret!!);found=emptyMap();balance="0.00000000 KAS";status="Kaspa TN10 키/주소 생성 ✓ · Cards에서 A/B/C 카드를 등록하세요";refresh()}},modifier=Modifier.fillMaxWidth()){Text("새 Testnet 지갑 생성")};Spacer(Modifier.height(8.dp));Text("기존 지갑 복구: Password 입력 후 서로 다른 NFC 카드 2장을 태그",style=MaterialTheme.typography.bodySmall)}}}
        if(showReceive&&address.isNotEmpty()){Spacer(Modifier.height(14.dp));val qrBitmap=remember(address){qr(address)};Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("Receive · TN10",fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Image(qrBitmap.asImageBitmap(),"Receive QR",Modifier.size(220.dp));Spacer(Modifier.height(8.dp));Text(shortKaspaAddress(address))}}}
        if(showSend){Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Send · TN10",fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));OutlinedTextField(sendTo,{sendTo=it.trim();sendReady=false;prepared=null},label={Text("받는 주소")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));OutlinedTextField(sendAmount,{sendAmount=it;sendReady=false;prepared=null},label={Text("보낼 수량 (KAS)")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));Button(onClick={preflight()},modifier=Modifier.fillMaxWidth()){Text("UTXO / 수수료 확인")};Spacer(Modifier.height(8.dp));Button(onClick={beginSend()},enabled=sendReady&&!awaitingSendCard,modifier=Modifier.fillMaxWidth()){Text(if(awaitingSendCard)"NFC 카드를 태그하세요..." else "NFC + 생체인증 후 전송")};if(sendInfo.isNotBlank()){Spacer(Modifier.height(8.dp));Text(sendInfo)}}}}
        if(showCards){Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("NFC Cards",fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));if(address.isNotEmpty()&&pass.isBlank()){OutlinedTextField(pass,{pass=it;found=emptyMap()},label={Text("Recovery Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp))};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={prepare(Slot.A)},modifier=Modifier.weight(1f)){Text("A 등록")};Button(onClick={prepare(Slot.B)},modifier=Modifier.weight(1f)){Text("B 등록")};Button(onClick={prepare(Slot.C)},modifier=Modifier.weight(1f)){Text("C 등록")}};Spacer(Modifier.height(10.dp));Text("2-of-3 Recovery · 서로 다른 카드 2장으로 복구",style=MaterialTheme.typography.bodySmall)}}}
        if(showSettings){Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Settings",fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));Text("표시 통화   KRW");Text("생체인증   Galaxy Biometric");Text("네트워크   TN10 Testnet");Text("버전       ${BuildConfig.VERSION_NAME}")}}}
        Spacer(Modifier.height(12.dp));Card(Modifier.fillMaxWidth()){Text(status,Modifier.padding(14.dp))};Spacer(Modifier.height(14.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={showSend=false;showReceive=false;showCards=false;showSettings=false}){Text("Wallet",fontWeight=FontWeight.Bold)};TextButton(onClick={status="거래내역은 다음 단계에서 연결합니다"}){Text("Transactions")};TextButton(onClick={showCards=!showCards;showSend=false;showReceive=false;showSettings=false}){Text("Cards")};TextButton(onClick={showSettings=!showSettings;showSend=false;showReceive=false;showCards=false}){Text("Settings")}};Spacer(Modifier.height(6.dp));Text("v${BuildConfig.VERSION_NAME} · TN10 TESTNET ONLY",fontSize=11.sp,color=KaspaTealDark,modifier=Modifier.align(Alignment.CenterHorizontally));Spacer(Modifier.height(18.dp))
    }}
}
