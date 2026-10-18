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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONObject
import java.util.Locale
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
            override fun onAuthenticationFailed(){ /* Prompt remains active; wait for success or cancellation. */ }
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Kaspa NFC Wallet").setSubtitle("송금 보안 작업을 승인하세요").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("취소").build())
    }
    override fun onResume(){super.onResume();val i=Intent(this,javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);nfc?.enableForegroundDispatch(this,PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_MUTABLE),null,null)}
    override fun onPause(){nfc?.disableForegroundDispatch(this);super.onPause()}
    override fun onNewIntent(i:Intent){super.onNewIntent(i);val t=if(android.os.Build.VERSION.SDK_INT>=33)i.getParcelableExtra(NfcAdapter.EXTRA_TAG,Tag::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(NfcAdapter.EXTRA_TAG);if(t!=null)onTag?.invoke(t)}
}

internal enum class Slot(val n:Int){A(1),B(2),C(3)}
private data class Rec(val slot:Slot,val daily:ByteArray,val recovery:ByteArray)
private sealed class ReadResult{data class Ok(val rec:Rec):ReadResult();data class Error(val msg:String):ReadResult()}

internal object Crypto{
    private val rng=SecureRandom();private const val KS="AndroidKeyStore";private const val ALIAS="nkw_v05_daily"
    private fun dev():SecretKey{val s=KeyStore.getInstance(KS).apply{load(null)};(s.getKey(ALIAS,null) as? SecretKey)?.let{return it};val g=KeyGenerator.getInstance("AES",KS);g.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setUserAuthenticationRequired(true).apply { if(android.os.Build.VERSION.SDK_INT>=30) setUserAuthenticationParameters(30,KeyProperties.AUTH_BIOMETRIC_STRONG) else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(30) }.build());return g.generateKey()}
    fun random(n:Int)=ByteArray(n).also{rng.nextBytes(it)}
    fun dailyEnc(p:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,dev());return c.iv+c.doFinal(p)}
    fun dailyDec(b:ByteArray):ByteArray{require(b.size>=60){"daily payload length ${b.size}"};val iv=b.copyOfRange(0,12);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,dev(),GCMParameterSpec(128,iv));return c.doFinal(b.copyOfRange(12,b.size))}
    private fun passKey(pass:String,salt:ByteArray):SecretKey{val f=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");return SecretKeySpec(f.generateSecret(PBEKeySpec(pass.toCharArray(),salt,120000,256)).encoded,"AES")}
    fun passEnc(p:ByteArray,pass:String):ByteArray{val salt=random(16);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,passKey(pass,salt));return salt+c.iv+c.doFinal(p)}
    fun passDec(b:ByteArray,pass:String):ByteArray{val salt=b.copyOfRange(0,16);val iv=b.copyOfRange(16,28);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,passKey(pass,salt),GCMParameterSpec(128,iv));return c.doFinal(b.copyOfRange(28,b.size))}
}

internal object Shares{
    private fun mul(a:Int,b:Int):Int{var x=a;var y=b;var r=0;repeat(8){if((y and 1)!=0)r=r xor x;val hi=x and 0x80;x=(x shl 1) and 255;if(hi!=0)x=x xor 0x1b;y=y shr 1};return r}
    private fun pow(a:Int,n:Int):Int{var r=1;var x=a;var e=n;while(e>0){if((e and 1)!=0)r=mul(r,x);x=mul(x,x);e=e shr 1};return r}
    private fun div(a:Int,b:Int)=mul(a,pow(b,254))
    fun split(secret:ByteArray):Map<Slot,ByteArray>{val slope=Crypto.random(secret.size);return Slot.entries.associateWith{s->ByteArray(secret.size+1).also{out->out[0]=s.n.toByte();for(i in secret.indices)out[i+1]=(secret[i].toInt() xor mul(slope[i].toInt() and 255,s.n)).toByte()}}}
    fun restore(a:ByteArray,b:ByteArray):Map<Slot,ByteArray>{
        require(a.size==33 && b.size==33){"복구 데이터 길이 오류"}
        val x1=a[0].toInt() and 255;val x2=b[0].toInt() and 255
        require(x1 in 1..3 && x2 in 1..3 && x1!=x2){"서로 다른 카드가 필요합니다"}
        return Slot.entries.associateWith { slot -> ByteArray(33).also { out ->
            out[0]=slot.n.toByte()
            for(i in 1..32){val y1=a[i].toInt() and 255;val y2=b[i].toInt() and 255
                val slope=div(y1 xor y2,x1 xor x2)
                out[i]=(y1 xor mul(slope,x1 xor slot.n)).toByte()
            }
        } }
    }
    fun join(a:ByteArray,b:ByteArray):ByteArray{
        restore(a,b) // Validate before accessing the share coordinates.
        val x1=a[0].toInt() and 255;val x2=b[0].toInt() and 255
        return ByteArray(32){i->val y1=a[i+1].toInt() and 255;val y2=b[i+1].toInt() and 255
            (y1 xor mul(div(y1 xor y2,x1 xor x2),x1)).toByte()}
    }
}

private object Card{
    private val key=MifareClassic.KEY_DEFAULT;private val magic=byteArrayOf(0x4e,0x4b,0x57,0x35);private val sectors=1..5;private const val MAX=5
    private fun auth(m:MifareClassic,sec:Int):Boolean{repeat(MAX){if(runCatching{m.authenticateSectorWithKeyA(sec,key)}.getOrDefault(false))return true;Thread.sleep(60)};return false}
    fun write(t:Tag,s:Slot,d:ByteArray,r:ByteArray,progress:(String)->Unit):String{val payload=ByteArray(240);magic.copyInto(payload);payload[4]=s.n.toByte();payload[5]=d.size.toByte();payload[6]=r.size.toByte();d.copyInto(payload,7);r.copyInto(payload,7+d.size);payload[239]=sum(payload,239);var last="";for(attempt in 0 until MAX){progress("CARD ${s.name} 쓰는 중... (${attempt+1}/$MAX)");val m=MifareClassic.get(t)?:return "MIFARE Classic 카드가 아닙니다";try{m.connect();var off=0;var ok=true;for(sec in sectors){if(!auth(m,sec)){last="Sector $sec 인증 실패";ok=false;break};val first=m.sectorToBlock(sec);for(j in 0..2){m.writeBlock(first+j,payload.copyOfRange(off,off+16));off+=16}};if(ok)return "CARD ${s.name} 저장 완료 ✓"}catch(e:Exception){last="${e.javaClass.simpleName}: ${e.message?:""}"}finally{runCatching{m.close()}};Thread.sleep(80)};return "CARD ${s.name} 쓰기 실패 ✕ (5/5) · 카드를 다시 태그하세요 · $last"}
    fun read(t:Tag,progress:(String)->Unit):ReadResult{var last="";for(attempt in 0 until MAX){progress("카드 읽는 중... (${attempt+1}/$MAX)");val m=MifareClassic.get(t)?:return ReadResult.Error("읽기 실패: MIFARE Classic 카드가 아님");try{m.connect();val p=ByteArray(240);var off=0;var ok=true;for(sec in sectors){if(!auth(m,sec)){last="Sector $sec Key A 인증 실패";ok=false;break};val first=m.sectorToBlock(sec);for(j in 0..2){m.readBlock(first+j).copyInto(p,off);off+=16}};if(ok){if(!p.copyOfRange(0,4).contentEquals(magic))return ReadResult.Error("읽기 실패: 등록된 카드 포맷이 아닙니다");if(p[239]!=sum(p,239))return ReadResult.Error("읽기 실패: Checksum 불일치");val s=Slot.entries.firstOrNull{it.n==(p[4].toInt() and 255)}?:return ReadResult.Error("읽기 실패: Slot 값 오류");val dl=p[5].toInt() and 255;val rl=p[6].toInt() and 255;if(dl<1||rl<1||7+dl+rl>239)return ReadResult.Error("읽기 실패: 데이터 길이 오류");return ReadResult.Ok(Rec(s,p.copyOfRange(7,7+dl),p.copyOfRange(7+dl,7+dl+rl)))}}catch(e:Exception){last="${e.javaClass.simpleName}: ${e.message?:""}"}finally{runCatching{m.close()}};Thread.sleep(80)};return ReadResult.Error("카드 읽기 실패 ✕ (5/5) · 카드를 다시 태그하세요 · $last")}
    private fun sum(d:ByteArray,n:Int):Byte{var v=0;for(i in 0 until n)v=v xor(d[i].toInt() and 255);return v.toByte()}
}


private object Tn10 {
    suspend fun balance(address: String): String = withContext(Dispatchers.IO) {
        KaspaAddress.testnetPayload(address)
        val c = URL("https://api-tn10.kaspa.org/addresses/$address/balance").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        try {
            check(c.responseCode in 200..299) { "잔액 조회 HTTP ${c.responseCode}" }
            val n = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getLong("balance")
            java.math.BigDecimal.valueOf(n, 8).toPlainString() + " KAS"
        } finally { c.disconnect() }
    }
}

private fun qr(text:String):Bitmap{val m=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,640,640);return Bitmap.createBitmap(640,640,Bitmap.Config.RGB_565).also{b->for(x in 0 until 640)for(y in 0 until 640)b.setPixel(x,y,if(m[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}

@Composable private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = KaspaInk)
            content()
        }
    }
}

@Composable private fun App(reg:(((Tag)->Unit)->Unit),bio:(((Boolean,String)->Unit)->Unit)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(WalletStore.loadAddress(context)) }
    var balance by remember { mutableStateOf("— KAS") }
    var status by remember { mutableStateOf(if(address.isBlank()) "새 지갑을 만들거나 카드 2장으로 복구하세요" else "저장된 지갑을 불러왔습니다") }
    var tab by rememberSaveable { mutableStateOf("Wallet") }
    var page by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf<ByteArray?>(null) }
    var shares by remember { mutableStateOf<Map<Slot,ByteArray>>(emptyMap()) }
    var found by remember { mutableStateOf<Map<Slot,ByteArray>>(emptyMap()) }
    var recoveryMode by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Slot?>(null) }
    var daily by remember { mutableStateOf<ByteArray?>(null) }
    var cardMarks by remember { mutableStateOf(WalletStore.loadCards(context)) }
    var history by remember { mutableStateOf(WalletStore.history(context)) }
    var sendTo by rememberSaveable { mutableStateOf("") }
    var sendAmount by rememberSaveable { mutableStateOf("") }
    var prepared by remember { mutableStateOf<Tn10Transaction.Prepared?>(null) }
    var sendInfo by remember { mutableStateOf("") }
    var awaitingSendCard by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var resultTx by remember { mutableStateOf("") }
    var confirmSend by remember { mutableStateOf(false) }
    var discardUnbacked by remember { mutableStateOf(false) }
    var inputRevision by remember { mutableIntStateOf(0) }

    fun refresh() {
        if(address.isBlank() || refreshing) return
        val requested = address
        refreshing = true
        scope.launch {
            try { val value = Tn10.balance(requested); if(address == requested) balance = value }
            catch(e:Exception) { status = "잔액 조회 실패 · ${e.message}" }
            finally { refreshing = false }
        }
    }
    fun saveWallet(key: ByteArray) {
        val next = KaspaAddress.testnet(key)
        check(address.isBlank() || address == next) { "현재 지갑과 다른 카드입니다" }
        WalletStore.saveAddress(context, next)
        address = next
        secret?.fill(0); secret = key.copyOf()
    }
    fun copy(text:String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kaspa TN10",text))
        status = "복사했습니다"
    }
    fun cancelOperation() {
        if(busy) return
        awaitingSendCard=false; target=null; daily?.fill(0); daily=null
        recoveryMode=false; found.values.forEach { it.fill(0) }; found=emptyMap()
        status="작업을 취소했습니다"
    }
    fun prepareCard(slot:Slot) {
        if(secret == null || pass.length < 8) { status="카드 등록은 먼저 복구 비밀번호와 카드 2장으로 지갑을 해제하세요"; return }
        busy=true
        bio { ok,msg ->
            if(ok) try { daily=Crypto.dailyEnc(secret!!); target=slot; recoveryMode=false; status="CARD ${slot.name}를 태그하세요 · 저장 후 자동 검증합니다" }
            catch(e:Exception) { status="카드 준비 실패 · ${e.message}" }
            else status=msg
            busy=false
        }
    }
    fun preflight() {
        prepared=null; confirmSend=false; resultTx=""
        val revision=inputRevision; val from=address; val to=sendTo; val amount=sendAmount
        busy=true; sendInfo="UTXO와 수수료를 확인하고 있습니다"
        scope.launch {
            try {
                val p=Tn10Transaction.prepare(from,to,amount)
                if(revision==inputRevision && address==from) { prepared=p; confirmSend=true; sendInfo="송금 내용을 확인하세요" }
            } catch(e:Exception) { sendInfo="송금 준비 실패 · ${e.message}" }
            finally { busy=false }
        }
    }
    // The callback is replaced after recomposition, so it always observes current input/state.
    SideEffect { reg { tag ->
        if(!busy && (awaitingSendCard || target!=null || recoveryMode)) {
            busy=true
            scope.launch {
                try {
                    val writeSlot=target
                    if(writeSlot!=null) {
                        val cardId=tag.id.joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(WalletStore.cardOwner(context,cardId).let { it==null || it==writeSlot.name }) { "이미 다른 슬롯에 등록된 카드입니다. 다른 실물 카드를 사용하세요" }
                        val d=daily?:error("카드 준비가 만료되었습니다")
                        val share=shares[writeSlot]?:error("복구 데이터가 없습니다. 카드 2장으로 해제하세요")
                        val encrypted=withContext(Dispatchers.Default) { Crypto.passEnc(share,pass) }
                        status="CARD ${writeSlot.name} 쓰는 중 · 최대 5회 재시도"
                        val message=withContext(Dispatchers.IO) { Card.write(tag,writeSlot,d,encrypted) {} }
                        if(!message.contains("저장 완료")) error(message)
                        val check=withContext(Dispatchers.IO) { Card.read(tag) {} }
                        require(check is ReadResult.Ok && check.rec.slot==writeSlot && check.rec.daily.contentEquals(d) && check.rec.recovery.contentEquals(encrypted)) { "저장 검증 실패 · 같은 카드를 다시 태그하세요" }
                        WalletStore.bindCard(context,cardId,writeSlot.name)
                        cardMarks=cardMarks+writeSlot.name; WalletStore.saveCards(context,cardMarks)
                        target=null; daily?.fill(0); daily=null
                        status="CARD ${writeSlot.name} 저장 및 검증 완료 ✓"
                    } else {
                        status="카드 읽는 중 · 최대 5회 재시도"
                        val rr=withContext(Dispatchers.IO) { Card.read(tag) {} }
                        if(rr is ReadResult.Error) error(rr.msg)
                        val rec=(rr as ReadResult.Ok).rec
                        if(awaitingSendCard) {
                            awaitingSendCard=false
                            status="카드 확인 완료 · 생체인증을 진행하세요"
                            // Keep the operation locked across the biometric prompt and broadcast.
                            bio { ok,msg ->
                                if(!ok) { busy=false; sendInfo=msg; status=msg }
                                else scope.launch {
                                    var key:ByteArray?=null
                                    try {
                                        val unlocked=Crypto.dailyDec(rec.daily)
                                        key=unlocked
                                        check(KaspaAddress.testnet(unlocked)==address) { "현재 지갑의 카드가 아닙니다" }
                                        val p=prepared?:error("송금 준비가 만료되었습니다")
                                        sendInfo="서명 중입니다"
                                        val signingKey=unlocked
                                        val request=withContext(Dispatchers.Default) { KaspaTx.signAll(p.tx,signingKey); Tn10Transaction.signedRequest(p.tx) }
                                        unlocked.fill(0); key=null
                                        prepared=null; sendInfo="TN10 네트워크에 제출 중입니다"
                                        val txid=Tn10Broadcast.postTransaction(request)
                                        resultTx=txid
                                        WalletStore.record(context,address,txid,sendTo,sendAmount,p.fee)
                                        history=WalletStore.history(context)
                                        status="거래 제출 완료 ✓"; sendInfo="네트워크 제출 완료 · 거래내역에서 TXID를 확인하세요"
                                        refresh()
                                    } catch(e:Exception) { prepared=null; sendInfo="송금 처리 실패 · ${e.message}"; status="거래내역에서 제출 여부를 확인한 뒤 다시 시도하세요" }
                                    finally { key?.fill(0); busy=false }
                                }
                            }
                            return@launch
                        } else if(recoveryMode) {
                            val sh=withContext(Dispatchers.Default) { Crypto.passDec(rec.recovery,pass) }
                            require(sh.size==33 && sh[0].toInt()==rec.slot.n) { "카드와 복구 Share가 일치하지 않습니다" }
                            if(found.containsKey(rec.slot)) { sh.fill(0); status="이미 읽은 카드입니다. 다른 카드를 태그하세요" }
                            else {
                                found=found+(rec.slot to sh)
                                status="CARD ${rec.slot.name} 읽기 완료 · ${found.size}/2"
                                if(found.size>=2) {
                                    val two=found.values.take(2)
                                    val recovered=Shares.join(two[0],two[1])
                                    try {
                                        saveWallet(recovered)
                                        // Reuse the original polynomial rather than generating incompatible shares.
                                        shares.values.forEach { it.fill(0) }; shares=Shares.restore(two[0],two[1])
                                        cardMarks=cardMarks+found.keys.map { it.name }; WalletStore.saveCards(context,cardMarks)
                                        status="복구 완료 ✓ · 지갑은 다음 실행부터 자동으로 열립니다"
                                        recoveryMode=false; refresh()
                                    } finally { recovered.fill(0); found.values.forEach { it.fill(0) }; found=emptyMap() }
                                }
                            }
                        }
                    }
                } catch(e:Exception) { status="처리 실패 · ${e.message ?: "비밀번호 또는 카드 데이터를 확인하세요"}" }
                busy=false
            }
        }
    } }
    LaunchedEffect(address) { if(address.isNotBlank()) refresh() }
    DisposableEffect(Unit) { onDispose { secret?.fill(0); daily?.fill(0); shares.values.forEach { it.fill(0) }; found.values.forEach { it.fill(0) }; reg {} } }

    if(discardUnbacked) AlertDialog(onDismissRequest={discardUnbacked=false},title={Text("미등록 지갑 닫기")},text={Text("카드를 한 장도 등록하지 않은 지갑 주소를 이 기기에서 제거합니다. 이 주소로 코인을 받았다면 먼저 기존 카드로 복구하세요.")},confirmButton={TextButton(onClick={
        WalletStore.clearUnbacked(context); address="";balance="— KAS";pass="";discardUnbacked=false;tab="Wallet";page="";status="새 지갑을 만들거나 카드로 복구하세요"
    }){Text("주소 제거")}},dismissButton={TextButton(onClick={discardUnbacked=false}){Text("취소")}})
    if(confirmSend && prepared!=null) AlertDialog(
        onDismissRequest={confirmSend=false}, title={Text("송금 확인")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("받는 주소\n$sendTo"); Text("수량  $sendAmount KAS")
            Text("수수료  ${java.math.BigDecimal.valueOf(prepared!!.fee,8).toPlainString()} KAS")
            Text("TN10 테스트넷 · NFC 카드 1장과 생체인증 후 제출합니다")
        }},
        confirmButton={Button(onClick={confirmSend=false; awaitingSendCard=true; status="송금 인증 1/2 · NFC 카드 1장을 태그하세요"; sendInfo=status}) { Text("카드 인증 시작") }},
        dismissButton={TextButton(onClick={confirmSend=false}) { Text("취소") }}
    )
    Scaffold(containerColor=KaspaSurface, bottomBar={
        NavigationBar(containerColor=Color.White) {
            listOf("Wallet" to "지갑","Transactions" to "거래내역","Cards" to "카드관리","Settings" to "설정").forEach { (id,label) ->
                NavigationBarItem(selected=tab==id,onClick={if(!busy && !awaitingSendCard && target==null && !recoveryMode){tab=id;page=""}},
                    icon={Text(when(id){"Wallet"->"◈";"Transactions"->"↔";"Cards"->"▣";else->"⚙"},fontSize=22.sp)},label={Text(label,fontSize=11.sp)})
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text("Kaspa NFC Wallet",fontSize=23.sp,fontWeight=FontWeight.Bold);Text("NFC + 생체인증",color=KaspaTealDark,fontSize=12.sp)}
                Surface(color=KaspaMint,shape=RoundedCornerShape(50)){Text("TN10",Modifier.padding(12.dp),color=KaspaTealDark,fontWeight=FontWeight.Bold)}
            }
            if(address.isBlank()) {
                Panel("지갑 시작하기") {
                    Text("새 지갑을 생성하거나 기존 NFC 카드 2장으로 복구하세요.")
                    OutlinedTextField(pass,{if(!busy && !recoveryMode)pass=it},label={Text("복구 비밀번호 · 8자 이상")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!busy&&!recoveryMode,singleLine=true)
                    Button(onClick={
                        val key=KaspaAddress.newPrivateKey()
                        try { saveWallet(key); shares=Shares.split(key); status="지갑 생성 완료 · 카드 A/B/C를 모두 등록하세요";tab="Cards" }
                        catch(e:Exception){status="지갑 저장 실패 · ${e.message}"}
                        finally {key.fill(0)}
                    },enabled=pass.length>=8&&!busy&&!recoveryMode,modifier=Modifier.fillMaxWidth()){Text("새 Testnet 지갑 생성")}
                    OutlinedButton(onClick={recoveryMode=true;status="서로 다른 NFC 카드 2장을 태그하세요"},enabled=pass.length>=8&&!busy&&!recoveryMode,modifier=Modifier.fillMaxWidth()){Text("기존 카드로 복구")}
                }
            } else when(tab) {
                "Wallet" -> {
                    if(page.isBlank()) {
                        Card(colors=CardDefaults.cardColors(containerColor=KaspaMint),shape=RoundedCornerShape(26.dp)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("TOTAL BALANCE",color=KaspaTealDark,fontSize=12.sp)
                                Text(balance,fontSize=30.sp,fontWeight=FontWeight.Bold)
                                Text("TN10 테스트 코인 · 현금 가치 없음",fontSize=12.sp,color=KaspaTealDark)
                                TextButton(onClick={refresh()},enabled=!refreshing) {Text(if(refreshing)"조회 중…" else "잔액 새로고침")}
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Button(onClick={page="Send";resultTx=""},enabled=cardMarks.size>=2,modifier=Modifier.weight(1f).height(54.dp)){Text("Send · 보내기")}
                            OutlinedButton(onClick={page="Receive"},enabled=cardMarks.size>=2,modifier=Modifier.weight(1f).height(54.dp)){Text("Receive · 받기")}
                        }
                        Panel("지갑 주소") {Text(shortKaspaAddress(address));TextButton(onClick={copy(address)}){Text("주소 복사")}}
                        WalletSecurityStatus("A" in cardMarks,"B" in cardMarks,"C" in cardMarks)
                        if(cardMarks.size<2) Text("수신 전에 카드 2장 이상을 등록하세요. 카드 3장 모두 등록을 권장합니다.",color=KaspaTealDark)
                    } else if(page=="Receive") {
                        TextButton(onClick={page=""}){Text("‹ 지갑으로")}
                        Panel("Receive · 받기") {
                            val bitmap=remember(address){qr(address)}
                            Image(bitmap.asImageBitmap(),"TN10 수신 주소 QR",Modifier.size(230.dp).align(Alignment.CenterHorizontally))
                            Text(address,style=MaterialTheme.typography.bodySmall)
                            Button(onClick={copy(address)},modifier=Modifier.fillMaxWidth()){Text("주소 복사")}
                            Text("TN10 테스트넷 주소입니다",color=KaspaTealDark)
                        }
                    } else {
                        TextButton(onClick={if(!busy&&!awaitingSendCard){prepared=null;page=""}},enabled=!busy&&!awaitingSendCard){Text("‹ 지갑으로")}
                        Panel(if(resultTx.isBlank()) "Send · 보내기" else "네트워크 제출 완료 ✓") {
                            if(resultTx.isNotBlank()) {
                                Text("TXID");Text(resultTx,style=MaterialTheme.typography.bodySmall)
                                Text("블록 확인까지 시간이 걸릴 수 있습니다")
                                TextButton(onClick={copy(resultTx)}){Text("TXID 복사")}
                                Button(onClick={page="";sendTo="";sendAmount=""}){Text("완료")}
                            } else {
                                OutlinedTextField(sendTo,{sendTo=it.trim();prepared=null;inputRevision++},label={Text("받는 주소 · kaspatest:")},enabled=!busy&&!awaitingSendCard,modifier=Modifier.fillMaxWidth())
                                OutlinedTextField(sendAmount,{sendAmount=it;prepared=null;inputRevision++},label={Text("보낼 수량 (KAS)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy&&!awaitingSendCard,modifier=Modifier.fillMaxWidth(),singleLine=true)
                                Button(onClick={preflight()},enabled=!busy&&!awaitingSendCard,modifier=Modifier.fillMaxWidth()){Text("다음 · 수수료 확인")}
                                if(sendInfo.isNotBlank()) Text(sendInfo)
                            }
                        }
                    }
                }
                "Transactions" -> Panel("거래내역") {
                    Text("이 기기에서 제출한 거래 · TN10",color=KaspaTealDark,fontSize=12.sp)
                    val records=history.filter{it.wallet==address}
                    if(records.isEmpty()) Text("아직 제출한 거래가 없습니다")
                    records.forEach { tx ->
                        HorizontalDivider()
                        Text("보내기 · ${tx.amount} KAS",fontWeight=FontWeight.Bold)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(tx.time)),fontSize=12.sp)
                        Text("수신: ${shortKaspaAddress(tx.to)}",fontSize=12.sp)
                        Text("수수료: ${java.math.BigDecimal.valueOf(tx.fee,8).toPlainString()} KAS",fontSize=12.sp)
                        Text("제출됨 · ${tx.txid.take(16)}…",fontSize=12.sp)
                        TextButton(onClick={copy(tx.txid)}){Text("TXID 복사")}
                    }
                }
                "Cards" -> {
                    WalletSecurityStatus("A" in cardMarks,"B" in cardMarks,"C" in cardMarks)
                    Panel("카드 관리") {
                        Text("앱 재실행 후에도 주소와 잔액은 자동으로 열립니다. 카드 등록에는 복구 비밀번호와 기존 카드 2장이 필요합니다.")
                        if(secret==null) {
                            OutlinedTextField(pass,{pass=it},label={Text("복구 비밀번호")},visualTransformation=PasswordVisualTransformation(),enabled=!busy&&!recoveryMode,modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Button(onClick={recoveryMode=true;status="등록된 카드 2장을 태그하세요 · 복구 데이터를 보존합니다"},enabled=pass.length>=8&&!busy&&!recoveryMode){Text("카드 2장으로 등록 잠금 해제")}
                        }
                        Slot.entries.forEach { slot ->
                            OutlinedButton(onClick={prepareCard(slot)},enabled=secret!=null&&!busy&&target==null&&!recoveryMode,modifier=Modifier.fillMaxWidth()){Text("CARD ${slot.name} · ${if(slot.name in cardMarks)"재등록" else "등록"}")}
                        }
                        Text("2-of-3 복구 · 등록 이력은 이 기기 기준",fontSize=12.sp,color=KaspaTealDark)
                    }
                }
                "Settings" -> Panel("설정") {
                    Text("네트워크   TN10 Testnet")
                    Text("송금 인증   NFC 카드 1장 + 생체인증")
                    Text("복구   카드 2장 + 복구 비밀번호")
                    Text("지갑 주소 자동 저장   사용 중")
                    Text("개인키는 앱 저장소에 저장하지 않습니다")
                    Text("버전   ${BuildConfig.VERSION_NAME}")
                    if(cardMarks.isEmpty() && secret==null) OutlinedButton(onClick={discardUnbacked=true}) {Text("미등록 지갑 닫기") }
                }
            }
            if(busy || refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(color=KaspaMint,shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(if(busy)"처리 중 · $status" else status,style=MaterialTheme.typography.bodySmall)
                    if(awaitingSendCard || target!=null || recoveryMode) TextButton(onClick={cancelOperation()},enabled=!busy){Text("대기 취소")}
                }
            }
            VersionFooter()
        }
    }
}
