package com.nfckaspa.wallet

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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

internal fun qr(text:String):Bitmap{val m=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,640,640);return Bitmap.createBitmap(640,640,Bitmap.Config.RGB_565).also{b->for(x in 0 until 640)for(y in 0 until 640)b.setPixel(x,y,if(m[x,y])android.graphics.Color.BLACK else android.graphics.Color.WHITE)}}

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
    var displayedFee by remember { mutableStateOf<Long?>(null) }
    var sentAt by remember { mutableLongStateOf(0) }
    var sendAll by remember { mutableStateOf(false) }
    var settingDialog by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(WalletStore.currency(context)) }
    var quote by remember { mutableStateOf<MarketQuote?>(null) }
    var discardUnbacked by remember { mutableStateOf(false) }
    var inputRevision by remember { mutableIntStateOf(0) }
    val scanner=rememberLauncherForActivityResult(ScanContract()) { result ->
        val value=result.contents
        if(value!=null) {
            val parsed=value.trim().substringBefore('?')
            if(runCatching{KaspaAddress.testnetPayload(parsed)}.isSuccess){sendTo=parsed;prepared=null;displayedFee=null;sendAll=false;inputRevision++;tab="Wallet";page="Send"}
            else Toast.makeText(context,"TN10 수신 주소 QR이 아닙니다",Toast.LENGTH_LONG).show()
        }
    }
    fun launchScan(){scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Kaspa TN10 주소 QR을 스캔하세요").setBeepEnabled(false).setOrientationLocked(false))}

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
        Toast.makeText(context,"복사했습니다",Toast.LENGTH_SHORT).show()
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
        prepared=null; displayedFee=null; resultTx=""
        val revision=inputRevision; val from=address; val to=sendTo; val amount=sendAmount
        busy=true; sendInfo="UTXO와 수수료를 확인하고 있습니다"
        scope.launch {
            try {
                val p=if(sendAll) Tn10Transaction.prepareMaximum(from,to) else Tn10Transaction.prepare(from,to,amount)
                if(revision==inputRevision && address==from) {
                    prepared=p;displayedFee=p.fee
                    if(sendAll)sendAmount=java.math.BigDecimal.valueOf(p.tx.outputs.first().amount,8).stripTrailingZeros().toPlainString()
                    page="Confirm";sendInfo="송금 내용을 확인하세요"
                }
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
                        Toast.makeText(context,status,Toast.LENGTH_LONG).show()
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
                                        resultTx=txid;displayedFee=p.fee;sentAt=System.currentTimeMillis();page="Success"
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
                                        Toast.makeText(context,status,Toast.LENGTH_LONG).show()
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
    BackHandler(enabled=page.isNotBlank() || tab!="Wallet") {
        if(!busy&&!awaitingSendCard&&target==null&&!recoveryMode){page="";tab="Wallet";prepared=null}
    }
    LaunchedEffect(Unit) { quote=runCatching{MarketQuote.fetch()}.getOrNull() }
    if(settingDialog.isNotBlank()) {
        val messages=mapOf(
            "language" to ("언어" to "현재 한국어 화면을 제공합니다."),
            "theme" to ("테마" to "첨부 시안의 Kaspa Teal 테마가 적용되어 있습니다."),
            "biometric" to ("생체인증" to "송금 서명은 등록된 NFC 카드와 강한 생체인증을 모두 완료해야 진행됩니다."),
            "lock" to ("개인키 잠금" to "개인키는 NFC 카드의 암호문으로 보관됩니다. 앱에는 주소와 공개 메타데이터만 저장합니다."),
            "network" to ("네트워크" to "Kaspa Testnet (TN10) 전용입니다. 메인넷 전송은 지원하지 않습니다."),
            "node" to ("노드" to "https://api-tn10.kaspa.org · 기본 공식 REST API"),
            "version" to ("앱 정보" to "NFC KASPA WALLET\nv${BuildConfig.VERSION_NAME}\nYour Keys on NFC · Secure · Simple · Yours"),
            "licenses" to ("오픈소스 라이선스" to "AndroidX / Jetpack Compose · Apache 2.0\nZXing / ZXing Android Embedded · Apache 2.0\nBouncy Castle · MIT\nRobolectric (검증 도구) · MIT"),
            "backup" to ("카드 등록 필요" to "NFC 카드 2장 이상을 등록한 뒤 송금·수신할 수 있습니다.")
        )
        val message=messages[settingDialog]
        AlertDialog(onDismissRequest={settingDialog=""},title={Text(if(settingDialog=="currency")"표시 통화" else message?.first?:"안내")},
            text={if(settingDialog=="currency")Column {
                listOf("KRW" to "대한민국 원 · KRW","USD" to "미국 달러 · USD").forEach { (id,label)->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=currency==id,onClick={currency=id;WalletStore.saveCurrency(context,id);settingDialog=""});Text(label)}
                }
                Text("시장 시세 참고 환산입니다. TN10 코인은 현금 가치가 없습니다.",fontSize=12.sp)
            } else Text(message?.second.orEmpty())},confirmButton={TextButton(onClick={settingDialog=""}){Text("확인")}})
    }
    val dates=cardMarks.associateWith { WalletStore.cardDate(context,it) }
    WalletDesign(WalletVisualState(
        address=address,balance=balance,tab=tab,page=page,to=sendTo,amount=sendAmount,fee=displayedFee,
        txid=resultTx,sentAt=sentAt,cards=cardMarks,dates=dates,history=history,status=status,sendInfo=sendInfo,
        busy=busy,refreshing=refreshing,awaiting=awaitingSendCard,recovery=recoveryMode,target=target?.name,
        unlocked=secret!=null,password=pass,currency=currency,krw=quote?.krw,usd=quote?.usd,change=quote?.change
    ),DesignActions(
        navigate={if(!busy&&!awaitingSendCard&&target==null&&!recoveryMode){tab=it;page=""}},
        route={next->if(!busy&&!awaitingSendCard){
            if((next=="Send"||next=="Receive")&&cardMarks.size<2)settingDialog="backup"
            else {page=next;if(next.isBlank()){prepared=null;resultTx="";sendInfo=""};if(next=="Send"){resultTx="";sendInfo=""}}
        }},
        copy={copy(it)},refresh={refresh();scope.launch{quote=runCatching{MarketQuote.fetch()}.getOrNull()}},
        scan={if(cardMarks.size<2)settingDialog="backup" else launchScan()},changeTo={sendTo=it.trim();prepared=null;displayedFee=null;sendAll=false;inputRevision++},
        changeAmount={sendAmount=it;prepared=null;displayedFee=null;sendAll=false;inputRevision++},
        preflight={preflight()},
        send={if(prepared==null){page="Send";sendInfo="수수료를 다시 확인하세요"}else{awaitingSendCard=true;status="송금 인증 1/2 · 등록된 NFC 카드를 태그하세요";sendInfo=status}},
        cancel={cancelOperation()},
        max={if(sendTo.isBlank())sendInfo="전액 입력 전에 받는 주소를 입력하세요" else {
            busy=true;sendInfo="전액 전송 수수료 계산 중…"
            scope.launch{try{val p=Tn10Transaction.prepareMaximum(address,sendTo);sendAmount=java.math.BigDecimal.valueOf(p.tx.outputs.first().amount,8).stripTrailingZeros().toPlainString();sendAll=true;displayedFee=p.fee;prepared=p;sendInfo="수수료를 제외한 최대 수량입니다"}catch(e:Exception){sendInfo="계산 실패 · ${e.message}"}finally{busy=false}}
        }},
        password={if(!busy&&!recoveryMode)pass=it},
        create={val key=KaspaAddress.newPrivateKey();try{saveWallet(key);shares=Shares.split(key);status="지갑 생성 완료 · 카드 A/B/C를 모두 등록하세요";tab="Cards"}catch(e:Exception){status="처리 실패 · ${e.message}"}finally{key.fill(0)}},
        recover={recoveryMode=true;status="서로 다른 등록 카드 2장을 태그하세요"},register={prepareCard(it)},
        explorer={id->if(Regex("[0-9a-fA-F]{64}").matches(id))runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://explorer-tn10.kaspa.org/txs/$id"))) }},
        setting={id->when(id){
            "paste"->{val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;sendTo=cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty();prepared=null;displayedFee=null;sendAll=false;inputRevision++}
            "recovery"->{if(cardMarks.size<2)settingDialog="backup" else {secret?.fill(0);secret=null;shares.values.forEach{it.fill(0)};shares=emptyMap();tab="Cards";status="복구 비밀번호를 입력하고 카드 2장으로 복구를 검증하세요"}}
            "discard"->discardUnbacked=true
            else->settingDialog=id
        }}
    ))
}
