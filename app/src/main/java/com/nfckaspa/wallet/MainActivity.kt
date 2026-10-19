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
    private var nfc:NfcAdapter?=null
    private var onTag:((Tag)->Unit)?=null
    private var pendingTag:Tag?=null
    private var prompt:BiometricPrompt?=null
    private var authGeneration=0L
    override fun onCreate(b:Bundle?) {
        super.onCreate(b)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        nfc=NfcAdapter.getDefaultAdapter(this)
        setContent{KaspaWalletTheme{App({onTag=it},{cipher,cb->authenticate(cipher,cb)})}}
    }
    private fun authenticate(cipher:Cipher,cb:(Cipher?,String)->Unit) {
        if(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){cb(null,"강한 생체인증을 사용할 수 없습니다");return}
        val generation=++authGeneration
        prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(r:BiometricPrompt.AuthenticationResult) {
                if(generation!=authGeneration)return
                val authenticated=r.cryptoObject?.cipher
                if(authenticated!==cipher){cb(null,"암호화 작업 인증 실패");return}
                cb(authenticated,"생체인증 성공")
            }
            override fun onAuthenticationError(code:Int,message:CharSequence){if(generation==authGeneration)cb(null,"생체인증 취소 또는 실패")}
        })
        prompt!!.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Kaspa NFC Wallet").setSubtitle("카드 데이터 암호화 작업 승인")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("취소").build(),BiometricPrompt.CryptoObject(cipher))
    }
    override fun onResume(){super.onResume();val i=Intent(this,javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);nfc?.enableForegroundDispatch(this,PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_MUTABLE),null,arrayOf(arrayOf(MifareClassic::class.java.name)))}
    override fun onPause(){nfc?.disableForegroundDispatch(this);super.onPause()}
    override fun onPostResume(){
        super.onPostResume()
        val tag=pendingTag;pendingTag=null
        if(tag!=null)onTag?.invoke(tag)
    }
    override fun onStop(){window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);pendingTag=null;authGeneration++;prompt?.cancelAuthentication();prompt=null;super.onStop()}
    override fun onNewIntent(i:Intent) {
        super.onNewIntent(i)
        if(i.action !in setOf(NfcAdapter.ACTION_TAG_DISCOVERED,NfcAdapter.ACTION_TECH_DISCOVERED,NfcAdapter.ACTION_NDEF_DISCOVERED))return
        // Android pauses a singleTop activity before onNewIntent. Consume only after resume.
        if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))return
        val tag=if(android.os.Build.VERSION.SDK_INT>=33)i.getParcelableExtra(NfcAdapter.EXTRA_TAG,Tag::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if(tag!=null)pendingTag=tag
    }
}

internal enum class Slot(val n:Int){A(1),B(2),C(3)}
private object MainnetBalance {
    suspend fun balance(address: String): String = withContext(Dispatchers.IO) {
        KaspaAddress.mainnetPayload(address)
        val n=JSONObject(KaspaNetwork.request("GET","/addresses/$address/balance")).getLong("balance")
        require(n>=0){"잔액 응답 오류"}
        java.math.BigDecimal.valueOf(n,8).toPlainString()+" KAS"
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

@Composable private fun App(reg:(((Tag)->Unit)->Unit),bio:((Cipher,(Cipher?,String)->Unit)->Unit)) {
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
    var pendingCard by remember { mutableStateOf<CardRecord?>(null) }
    var pendingUid by remember { mutableStateOf("") }
    var pendingSnapshot by remember { mutableStateOf<ByteArray?>(null) }
    var checkingSession by remember { mutableStateOf(false) }
    var cardPreparing by remember { mutableStateOf(false) }
    var resumingRegistration by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var foundGeneration by remember { mutableStateOf<String?>(null) }
    var recoveryVerified by remember { mutableStateOf(WalletStore.recoveryVerified(context)) }
    var cleanedCards by remember { mutableStateOf(WalletStore.cleanedCards(context)) }
    var overwritePrompt by remember { mutableStateOf<OverwritePermit?>(null) }
    var overwriteDescription by remember { mutableStateOf("") }
    var overwriteAccepted by remember { mutableStateOf(false) }
    var overwritePermit by remember { mutableStateOf<OverwritePermit?>(null) }
    var passConfirm by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newPassConfirm by remember { mutableStateOf("") }
    var provisioningPass by remember { mutableStateOf<CharArray?>(null) }
    var recoveryAddress by remember { mutableStateOf("") }
    var recoveryPub by remember { mutableStateOf<ByteArray?>(null) }
    var foundSecure by remember { mutableStateOf<Set<Slot>>(emptySet()) }
    var keepRecoveryKey by remember { mutableStateOf(false) }
    var backupVerified by remember { mutableStateOf(WalletStore.backupVerified(context)) }
    var sessionEpoch by remember { mutableLongStateOf(0) }
    var captureAllowed by remember { mutableStateOf(false) }
    var showGenerated by remember { mutableStateOf(false) }
    var cardMarks by remember { mutableStateOf(WalletStore.secureCards(context)) }
    var history by remember { mutableStateOf(WalletStore.history(context)) }
    var sendTo by rememberSaveable { mutableStateOf("") }
    var sendAmount by rememberSaveable { mutableStateOf("") }
    var prepared by remember { mutableStateOf<KaspaTransaction.Prepared?>(null) }
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
    var abandonDialog by remember { mutableStateOf(false) }
    var abandonAccepted by remember { mutableStateOf(false) }
    var abandonText by remember { mutableStateOf("") }
    var inputRevision by remember { mutableIntStateOf(0) }
    val scanner=rememberLauncherForActivityResult(ScanContract()) { result ->
        val value=result.contents
        if(value!=null) {
            val parsed=value.trim().substringBefore('?')
            if(runCatching{KaspaAddress.recipientPayload(parsed)}.isSuccess){sendTo=parsed;prepared=null;displayedFee=null;sendAll=false;inputRevision++;tab="Wallet";page="Send"}
            else {sendTo=parsed;prepared=null;displayedFee=null;sendAll=false;inputRevision++;tab="Wallet";page="Send";sendInfo="주소 형식·체크섬·공개키를 확인하세요"}
        }
    }
    fun launchScan(){scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Kaspa 메인넷 주소 QR을 스캔하세요").setBeepEnabled(false).setOrientationLocked(false))}

    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf("") }
    var historyOffset by remember(address) { mutableStateOf(0) }
    var historyMore by remember(address) { mutableStateOf(false) }
    fun refreshHistory(more:Boolean=false) {
        if(address.isBlank() || historyLoading) return
        val requested=address
        val offset=if(more)historyOffset else 0
        historyLoading=true;historyError=""
        scope.launch {
            try {
                val result=KaspaHistory.fetch(requested,offset)
                if(address==requested) {
                    WalletStore.mergeHistory(context,result.records)
                    history=WalletStore.history(context)
                    historyOffset=offset+result.count
                    historyMore=result.count==KaspaHistory.PAGE_SIZE
                }
            } catch(e:Exception) { if(address==requested)historyError="거래내역 조회 실패 · 다시 시도해 주세요 (${e.message})" }
            finally { historyLoading=false }
        }
    }
    fun refresh() {
        refreshHistory()
        if(address.isBlank() || refreshing) return
        val requested = address
        refreshing = true
        scope.launch {
            try { val value = MainnetBalance.balance(requested); if(address == requested) balance = value }
            catch(e:Exception) { status = "잔액 조회 실패 · ${e.message}" }
            finally { refreshing = false }
        }
    }
    fun saveWallet(key: ByteArray) {
        val next = KaspaAddress.mainnet(key)
        check(address.isBlank() || address == next) { "현재 지갑과 다른 카드입니다" }
        WalletStore.saveAddress(context, next)
        address = next
        secret?.fill(0); secret = key.copyOf()
    }
    fun copy(text:String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kaspa 메인넷",text))
        status = "복사했습니다"
        Toast.makeText(context,"복사했습니다",Toast.LENGTH_SHORT).show()
    }
    fun lockSecrets() {
        sessionEpoch++
        pendingUid="";pendingSnapshot=null;checkingSession=false;cardPreparing=false;cleaning=false;resumingRegistration=false;foundGeneration=null
        secret?.fill(0);secret=null;shares.values.forEach{it.fill(0)};shares=emptyMap()
        found.values.forEach{it.fill(0)};found=emptyMap();foundSecure=emptySet();recoveryPub=null
        provisioningPass?.fill('\u0000');provisioningPass=null
        pendingCard?.daily?.fill(0);pendingCard?.recovery?.fill(0);pendingCard=null
        pass="";passConfirm="";newPass="";newPassConfirm="";showGenerated=false
        target=null;recoveryMode=false;awaitingSendCard=false;overwritePrompt=null;overwritePermit=null
        prepared=null;busy=false
        if(page=="Confirm")page="Send"
    }
    fun cancelOperation() {
        if(busy)return
        lockSecrets();status="작업을 취소하고 비밀 정보를 잠갔습니다"
    }
    fun prepareCard(slot:Slot) {
        if(secret==null){status="카드 2장과 각 카드의 비밀번호로 먼저 잠금을 해제하세요";return}
        if(provisioningPass==null) {
            if(!PasswordPolicy.valid(newPass)||newPass!=newPassConfirm){status="새 복구 비밀번호와 재입력을 확인하세요";return}
            if(WalletStore.generation(context).isBlank()||WalletStore.recoveryVerified(context)) {
                WalletStore.beginProvision(context);cardMarks=emptySet();backupVerified=false;recoveryVerified=false;cleanedCards=emptySet()
            }
            if(cardMarks.isNotEmpty()) {
                checkingSession=true;target=slot
                status="중단된 등록을 이어갑니다. 이번 세대에 등록한 ${cardMarks.sorted().joinToString("/")} 카드 중 1장을 태그해 새 비밀번호를 확인하세요"
                return
            }
            provisioningPass=newPass.toCharArray()
        }
        pendingCard=null;pendingUid="";pendingSnapshot=null;overwritePermit=null;target=slot
        status="${slot.name} 카드를 태그해 내용을 확인한 뒤 생체인증하세요. 준비 후 같은 카드를 다시 태그합니다"
    }
    fun preflight() {
        if(WalletStore.pending(context)!=null){sendInfo="제출 결과 확인이 필요한 거래가 있습니다. 거래내역에서 기존 거래를 재확인하세요";return}
        prepared=null; displayedFee=null; resultTx=""
        val revision=inputRevision; val from=address; val to=sendTo; val amount=sendAmount
        busy=true; sendInfo="UTXO와 수수료를 확인하고 있습니다"
        scope.launch {
            try {
                val p=if(sendAll) KaspaTransaction.prepareMaximum(from,to) else KaspaTransaction.prepare(from,to,amount)
                if(revision==inputRevision && address==from) {
                    prepared=p;displayedFee=p.fee
                    if(sendAll)sendAmount=java.math.BigDecimal.valueOf(p.tx.outputs.first().amount,8).stripTrailingZeros().toPlainString()
                    page="Confirm";sendInfo="송금 내용을 확인하세요"
                }
            } catch(e:Exception) { sendInfo="송금 준비 실패 · ${e.message}" }
            finally { busy=false }
        }
    }
    SideEffect { ScreenCapturePolicy.apply((context as android.app.Activity).window,captureAllowed,showGenerated) }
    SideEffect { reg { tag ->
        if(!busy&&!cardPreparing&&overwritePrompt==null&&(awaitingSendCard||target!=null||recoveryMode||cleaning||resumingRegistration)) {
            val epoch=sessionEpoch
            busy=true
            scope.launch {
                var releaseBusy=true
                try {
                    check(!BuildConfig.DEBUG){"디버그 빌드는 실제 메인넷 카드를 읽거나 쓰지 않습니다"}
                    val snapshot=withContext(Dispatchers.IO){NfcCards.read(tag)}
                    check(epoch==sessionEpoch){"작업이 잠겼습니다"}
                    val writeSlot=target
                    val id=tag.id.joinToString(""){"%02x".format(it.toInt() and 255)}
                    if(resumingRegistration) {
                        val rec=snapshot.latest?:error("등록한 카드가 아닙니다")
                        RegistrationResume.validateRecord(rec,WalletStore.generation(context),WalletStore.secureCards(context),WalletStore.recoveryVerified(context),address,WalletStore.cardOwner(context,id))
                        val cipher=try{Crypto.decryptCipher(rec)}catch(e:Exception){error("이 휴대폰의 카드 암호화 키를 사용할 수 없습니다. 앱을 삭제하거나 생체정보를 변경했다면 카드 2장 복구가 필요합니다")}
                        bio(cipher) { authenticated,msg->
                            if(epoch!=sessionEpoch)return@bio
                            if(authenticated==null){busy=false;status=msg}
                            else scope.launch {
                                var key:ByteArray?=null;var share:ByteArray?=null
                                var restored:Map<Slot,ByteArray> = emptyMap()
                                val pw=pass.toCharArray();pass=""
                                try {
                                    val dailyKey=Crypto.finishDailyDecrypt(authenticated,rec);key=dailyKey
                                    val recoveredShare=withContext(Dispatchers.Default){Crypto.passDec(rec,pw)};share=recoveredShare
                                    check(epoch==sessionEpoch){"등록 재개 작업이 잠겼습니다"}
                                    restored=RegistrationResume.restore(rec,dailyKey,recoveredShare,WalletStore.generation(context),WalletStore.secureCards(context),WalletStore.recoveryVerified(context),address,WalletStore.cardOwner(context,id))
                                    // Persist only public progress; never persist the key, share or password.
                                    val marks=WalletStore.secureCards(context)+rec.slot.name
                                    WalletStore.bindCard(context,id,rec.slot.name);WalletStore.saveCards(context,marks);WalletStore.saveSecureCards(context,marks)
                                    saveWallet(dailyKey);shares.values.forEach{it.fill(0)};shares=restored;restored=emptyMap()
                                    provisioningPass?.fill('\u0000');provisioningPass=pw.copyOf()
                                    cardMarks=marks;backupVerified=false;recoveryVerified=false;cleanedCards=emptySet();resumingRegistration=false
                                    status="${rec.slot.name} 카드 인증 완료 · 같은 비밀번호로 미등록 카드를 이어서 등록하세요"
                                }catch(e:Exception){status="처리 실패 · 등록 재개 실패: ${e.message}. 비밀번호를 다시 입력하거나 대기를 취소하세요"}
                                finally{key?.fill(0);share?.fill(0);pw.fill('\u0000');restored.values.forEach{it.fill(0)};busy=false}
                            }
                        }
                        releaseBusy=false
                        return@launch
                    } else if(cleaning) {
                        check(WalletStore.recoveryVerified(context)){"현재 세대 카드 2장으로 먼저 복구 검증하세요"}
                        val rec=snapshot.latest?:error("등록 기록이 없습니다")
                        check(rec.version==3&&rec.generation.hex()==WalletStore.generation(context)&&rec.pub.contentEquals(KaspaAddress.mainnetPayload(address))&&WalletStore.cardOwner(context,id)==rec.slot.name){"이번 세대에 등록한 원래 카드를 태그하세요"}
                        val pw=pass.toCharArray()
                        val share=try{withContext(Dispatchers.Default){Crypto.passDec(rec,pw)}}finally{pw.fill('\u0000')}
                        try{check(share.size==33&&share[0].toInt()==rec.slot.n);withContext(Dispatchers.IO){NfcCards.cleanup(tag,rec,Crypto.sha(snapshot.bytes))}}
                        finally{share.fill(0)}
                        check(epoch==sessionEpoch)
                        WalletStore.markClean(context,rec.slot.name);cleanedCards=WalletStore.cleanedCards(context);backupVerified=WalletStore.backupVerified(context)
                        status="${rec.slot.name} 이전 영역 삭제 검증 완료 · ${cleanedCards.size}/3"
                        if(backupVerified){lockSecrets();status="새 세대 복구 검증 및 카드 3장 정리 완료 ✓"}
                    } else if(checkingSession&&writeSlot!=null) {
                        val rec=snapshot.latest?:error("현재 세대 카드가 아닙니다")
                        check(rec.version==3&&rec.generation.hex()==WalletStore.generation(context)&&rec.slot.name in cardMarks&&rec.pub.contentEquals(KaspaAddress.mainnetPayload(address))){"이번 등록 세대에 먼저 쓴 카드를 태그하세요"}
                        val pw=newPass.toCharArray()
                        try{val sh=withContext(Dispatchers.Default){Crypto.passDec(rec,pw)};sh.fill(0);check(epoch==sessionEpoch);provisioningPass=pw.copyOf()}
                        finally{pw.fill('\u0000')}
                        checkingSession=false;status="같은 비밀번호 확인 완료 · 등록할 ${writeSlot.name} 카드를 태그하세요"
                    } else if(writeSlot!=null) {
                        if(pendingCard==null) {
                            val rec=CardRecord(writeSlot,byteArrayOf(),byteArrayOf(),KaspaAddress.xOnlyPublicKey(secret?:error("잠금을 해제하세요")),snapshot.nextSequence(),3,WalletStore.generation(context).hexBytes())
                            val cipher=Crypto.encryptCipher();cardPreparing=true
                            bio(cipher) { authenticated,msg->
                                if(epoch!=sessionEpoch)return@bio
                                if(authenticated==null){cardPreparing=false;status=msg}
                                else scope.launch {
                                    try {
                                        val key=secret?:error("작업이 잠겼습니다")
                                        val daily=Crypto.finishDailyEncrypt(authenticated,key,rec.header())
                                        val pw=provisioningPass?.copyOf()?:error("비밀번호가 만료되었습니다")
                                        val encrypted=try{withContext(Dispatchers.Default){Crypto.passEnc(shares.getValue(writeSlot),pw,rec.header())}}finally{pw.fill('\u0000')}
                                        check(epoch==sessionEpoch)
                                        pendingCard=rec.copy(daily=daily,recovery=encrypted);pendingUid=id;pendingSnapshot=Crypto.sha(snapshot.bytes)
                                        status="준비 완료 · 같은 ${writeSlot.name} 카드를 다시 태그해 저장을 진행하세요"
                                    }catch(e:Exception){status="카드 준비 실패 · ${e.message}"}
                                    finally{cardPreparing=false}
                                }
                            }
                            return@launch
                        }
                        val rec=pendingCard?:error("카드를 다시 준비하세요")
                        if(id!=pendingUid||pendingSnapshot?.contentEquals(Crypto.sha(snapshot.bytes))!=true){pendingCard=null;pendingUid="";pendingSnapshot=null;overwritePermit=null;error("카드 또는 내용이 바뀌었습니다. 등록할 카드를 다시 태그해 준비하세요")}
                        val permit=overwritePermit
                        if(snapshot.occupied&&(permit==null||!permit.matches(id,snapshot.bytes,writeSlot,android.os.SystemClock.elapsedRealtime(),rec.encode()))) {
                            overwritePermit=null
                            overwritePrompt=OverwritePermit(id,Crypto.sha(snapshot.bytes),writeSlot,android.os.SystemClock.elapsedRealtime(),Crypto.sha(rec.encode()))
                            val old=snapshot.latest
                            val oldWallet=if(old?.pub?.size==32)KaspaAddress.fromPublicKey(old.pub) else "지갑을 식별할 수 없는 기존 데이터"
                            overwriteDescription="기존 등록: ${old?.slot?.name?:"알 수 없음"} 카드\n$oldWallet\n\n새 등록: 현재 지갑의 ${writeSlot.name} 카드\n\n계속하면 이 카드의 등록 내용이 바뀝니다. 다른 슬롯을 덮어쓰면 같은 슬롯 카드가 중복되어 기존 지갑을 복구하지 못할 수 있습니다. 기존 카드와 비밀번호로 복구 가능한지 먼저 확인하세요."
                            overwriteAccepted=false;busy=false;return@launch
                        }
                        overwritePermit=null // one attempt only; failures require another explicit approval.
                        withContext(Dispatchers.IO){NfcCards.write(tag,rec,Crypto.sha(snapshot.bytes))}
                        check(epoch==sessionEpoch){"작업이 잠겼습니다"}
                        val prior=WalletStore.cardOwner(context,id)
                        cardMarks=(cardMarks-prior.orEmpty())+writeSlot.name
                        WalletStore.bindCard(context,id,writeSlot.name);WalletStore.saveCards(context,cardMarks);WalletStore.saveSecureCards(context,cardMarks)
                        pendingCard=null;pendingUid="";pendingSnapshot=null;target=null
                        status="CARD ${writeSlot.name} 저장 및 검증 완료 ✓"
                        if(cardMarks.size==3){lockSecrets();status="카드 3장 저장 완료 · 비밀번호를 다시 입력하고 카드 2장으로 복구 검증하세요"}
                    } else if(awaitingSendCard) {
                        awaitingSendCard=false
                        val rec=snapshot.latest?:error("등록된 지갑 카드가 아닙니다")
                        check(rec.version==3&&WalletStore.backupVerified(context)&&rec.generation.hex()==WalletStore.generation(context)&&rec.pub.contentEquals(KaspaAddress.mainnetPayload(address))){"기존 카드는 카드 2장으로 복구 후 보안 카드로 재등록하세요"}
                        val cipher=Crypto.decryptCipher(rec)
                        bio(cipher) { authenticated,msg->
                            if(epoch!=sessionEpoch)return@bio
                            if(authenticated==null){busy=false;status=msg;sendInfo=msg}
                            else scope.launch {
                                var key:ByteArray?=null
                                try {
                                    key=Crypto.finishDailyDecrypt(authenticated,rec)
                                    check(KaspaAddress.mainnet(key!!)==address){"현재 지갑의 카드가 아닙니다"}
                                    val p=prepared?:error("송금 준비가 만료되었습니다")
                                    val from=address;val recipient=sendTo;val amount=sendAmount;val revision=inputRevision
                                    KaspaTransaction.validateIntent(p,from,recipient,amount)
                                    val signingKey=key!!
                                    val request=withContext(Dispatchers.Default){KaspaTx.signAll(p.tx,signingKey);KaspaTransaction.signedRequest(p.tx)}
                                    key?.fill(0);key=null
                                    check(epoch==sessionEpoch){"작업이 잠겼습니다"}
                                    check(address==from&&sendTo==recipient&&sendAmount==amount&&inputRevision==revision){"서명 중 송금 입력이 변경되었습니다. 제출하지 않았습니다"}
                                    val txid=KaspaTx.transactionId(p.tx)
                                    WalletStore.savePending(context,WalletStore.Pending(from,txid,request,recipient,amount,p.fee,System.currentTimeMillis()))
                                    WalletStore.record(context,from,txid,recipient,amount,p.fee)
                                    history=WalletStore.history(context)
                                    prepared=null;sendInfo="메인넷 네트워크에 제출 중입니다"
                                    val acknowledged=KaspaBroadcast.postTransaction(request)
                                    check(acknowledged==txid){"노드의 TXID가 서명 거래와 다릅니다"}
                                    // A submission acknowledgement is not proof of chain acceptance. Keep the payment lock until reconciliation.
                                    resultTx=txid;displayedFee=p.fee;sentAt=System.currentTimeMillis();page="Success"
                                    status="거래 제출 완료 ✓";sendInfo="네트워크 제출 완료";refresh()
                                } catch(e:Exception){prepared=null;sendInfo="송금 처리 실패 · ${e.message}";status="미확인 거래는 거래내역에서 기존 거래로 다시 확인하세요"}
                                finally{key?.fill(0);busy=false}
                            }
                        }
                        releaseBusy=false // The biometric callback owns the signing/submission lock.
                        return@launch
                    } else if(recoveryMode) {
                        val pw=pass.toCharArray()
                        val recoveredCard=try {withContext(Dispatchers.Default){
                            CardRecovery.openLatest(snapshot,pw)
                        }}finally{pw.fill('\u0000')}
                        val rec=recoveredCard?.first?:error("비밀번호 또는 카드 인증 데이터를 확인하세요")
                        val sh=recoveredCard.second
                        if(epoch!=sessionEpoch){sh.fill(0);error("작업이 잠겼습니다")}
                        try {
                            val expected=address.ifBlank{recoveryAddress.trim()}
                            if(rec.version==1)require(expected.isNotBlank()){"기존 카드의 지갑 주소를 입력하세요. 주소 없는 구버전 카드는 혼합 여부를 검증할 수 없습니다"}
                            if(rec.version>=2) {
                                if(expected.isNotBlank())require(rec.pub.contentEquals(KaspaAddress.mainnetPayload(expected))){"다른 지갑의 카드입니다"}
                                require(recoveryPub==null||recoveryPub!!.contentEquals(rec.pub)){"서로 다른 지갑의 카드입니다"}
                                recoveryPub=rec.pub.copyOf()
                            }
                            check(!found.containsKey(rec.slot)){"같은 슬롯 카드입니다. 서로 다른 카드가 필요합니다"}
                            found=found+(rec.slot to sh.copyOf())
                            val generation=if(rec.version==3)rec.generation.hex() else "legacy"
                            val mixed=foundGeneration!=null&&foundGeneration!=generation
                            if(foundGeneration==null)foundGeneration=generation
                            if(rec.version==3&&generation==WalletStore.generation(context))foundSecure=foundSecure+rec.slot
                            pass=""
                            status="CARD ${rec.slot.name} 확인 · ${found.size}/2 · 다음 카드의 비밀번호를 입력하세요"+(if(mixed)" · 등록 세대가 다릅니다. 복구는 가능하지만 백업 검증은 완료되지 않습니다" else "")
                            if(found.size>=2) {
                                val two=found.values.take(2);val recovered=Shares.join(two[0],two[1])
                                try {
                                    RecoveryIdentity.validate(recovered,expected,recoveryPub)
                                    saveWallet(recovered);shares.values.forEach{it.fill(0)};shares=Shares.restore(two[0],two[1])
                                    if(cardMarks.size==3&&foundSecure.size==2){WalletStore.verifyBackup(context);recoveryVerified=true;backupVerified=WalletStore.backupVerified(context)}
                                    found.values.forEach{it.fill(0)};found=emptyMap();foundSecure=emptySet();recoveryMode=false;recoveryPub=null
                                    if(!keepRecoveryKey&&recoveryVerified){lockSecrets();status="현재 세대 복구 검증 완료 ✓ · 새 비밀번호를 입력하고 카드 3장의 이전 영역을 정리하세요"}
                                    else status="복구 완료 · 보안 카드 등록용 새 비밀번호와 재입력을 확인하세요"
                                    refresh()
                                } finally {recovered.fill(0)}
                            }
                        } finally {sh.fill(0)}
                    }
                } catch(e:Exception){status="처리 실패 · ${e.message?:"카드와 비밀번호를 확인하세요"}"}
                finally{if(releaseBusy)busy=false}
            }
        }
    } }
    LaunchedEffect(address) { if(address.isNotBlank()) refresh() }
    DisposableEffect(context) {
        val lifecycle=(context as androidx.lifecycle.LifecycleOwner).lifecycle
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP){captureAllowed=false;lockSecrets()} }
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);lockSecrets();reg{}}
    }
    LaunchedEffect(secret,recoveryMode,target,resumingRegistration) {
        if(secret!=null||recoveryMode||target!=null||resumingRegistration){kotlinx.coroutines.delay(300_000);lockSecrets();status="보안 잠금 시간이 만료되었습니다"}
    }
    if(showGenerated)AlertDialog(properties=androidx.compose.ui.window.DialogProperties(securePolicy=androidx.compose.ui.window.SecureFlagPolicy.SecureOn),onDismissRequest={showGenerated=false},title={Text("새 복구 비밀번호")},
        text={Column{Text(if(address.isBlank())pass else newPass);Text("안전한 곳에 별도로 기록한 뒤 재입력하세요. 앱은 이 비밀번호를 저장하지 않습니다.")}},
        confirmButton={TextButton(onClick={showGenerated=false}){Text("기록했습니다")}})
    if(overwritePrompt!=null)OverwriteConsentDialog(overwriteDescription,overwriteAccepted,{overwriteAccepted=it},
        onContinue={overwritePermit=overwritePrompt!!.copy(created=android.os.SystemClock.elapsedRealtime());overwritePrompt=null;status="덮어쓰기를 승인했습니다. 같은 카드를 다시 태그하세요"},
        onCancel={overwritePrompt=null;overwritePermit=null;target=null;pendingCard=null;status="카드 저장을 취소했습니다"})




    val abandonSnapshot=remember(abandonDialog){if(abandonDialog)WalletStore.pending(context) else null}
    if(abandonDialog)AlertDialog(onDismissRequest={abandonDialog=false},title={Text("미확인 거래 잠금 수동 해제")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            val pending=abandonSnapshot
            Text("기존 거래 ${pending?.txid.orEmpty()}\n수신처 ${pending?.to.orEmpty()}\n금액 ${pending?.amount.orEmpty()} KAS")
            Text("이미 지급됐거나 나중에 지급될 수 있습니다. 기록 잠금 해제는 거래 취소가 아닙니다. 새로 송금하면 중복 지급될 수 있습니다. 거래내역과 Explorer를 먼저 확인하세요.")
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(abandonAccepted,{abandonAccepted=it});Text("중복 지급 위험을 이해하고 직접 해제를 요청합니다")}
            OutlinedTextField(abandonText,{abandonText=it},label={Text("내 지갑 주소 끝 8자 직접 입력")},singleLine=true)
        }},confirmButton={TextButton(enabled=abandonAccepted&&address.isNotBlank()&&abandonText==address.takeLast(8),onClick={
            runCatching{WalletStore.abandonPending(context,abandonAccepted,abandonText,abandonSnapshot?.txid.orEmpty())}.onSuccess{abandonDialog=false;status="사용자 요청으로 기록을 보관하고 잠금을 해제했습니다. 기존 거래의 취소는 보장되지 않습니다"}.onFailure{status="기록 해제 실패"}
        }){Text("위험 확인 후 해제")}},dismissButton={TextButton(onClick={abandonDialog=false}){Text("취소")}})
    if(discardUnbacked) AlertDialog(onDismissRequest={discardUnbacked=false},title={Text("미등록 지갑 닫기")},text={Text("카드를 한 장도 등록하지 않은 지갑 주소를 이 기기에서 제거합니다. 이 주소로 코인을 받았다면 먼저 기존 카드로 복구하세요.")},confirmButton={TextButton(onClick={
        WalletStore.clearUnbacked(context); address="";balance="— KAS";pass="";discardUnbacked=false;tab="Wallet";page="";status="새 지갑을 만들거나 카드로 복구하세요"
    }){Text("주소 제거")}},dismissButton={TextButton(onClick={discardUnbacked=false}){Text("취소")}})
    BackHandler(enabled=page.isNotBlank() || tab!="Wallet") {
        if(!busy&&!awaitingSendCard&&target==null&&!recoveryMode&&!cleaning&&!resumingRegistration&&!cardPreparing){page="";tab="Wallet";prepared=null}
    }
    LaunchedEffect(Unit) { quote=runCatching{MarketQuote.fetch()}.getOrNull() }
    if(settingDialog.isNotBlank()) {
        val messages=mapOf(
            "language" to ("언어" to "현재 한국어 화면을 제공합니다."),
            "theme" to ("테마" to "첨부 시안의 Kaspa Teal 테마가 적용되어 있습니다."),
            "biometric" to ("생체인증" to "송금 서명은 등록된 NFC 카드와 강한 생체인증을 모두 완료해야 진행됩니다."),
            "lock" to ("개인키 잠금" to "개인키는 NFC 카드의 암호문으로 보관됩니다. 앱에는 주소와 공개 메타데이터만 저장합니다."),
            "network" to ("네트워크" to "Kaspa 메인넷 전용입니다. kaspa: 주소로 실제 KAS를 송수신합니다."),
            "node" to ("노드" to "https://api.kaspa.org · 기본 공식 REST API"),
            "version" to ("앱 정보" to "Kaspa NFC Wallet\nv${BuildConfig.VERSION_NAME}\nYour Keys on NFC · Secure · Simple · Yours"),
            "licenses" to ("오픈소스 라이선스" to "AndroidX / Jetpack Compose · Apache 2.0\nZXing / ZXing Android Embedded · Apache 2.0\nBouncy Castle · MIT\nRobolectric (검증 도구) · MIT"),
            "backup" to ("백업 검증 필요" to "카드 3장 등록·2장 복구 검증·3장 이전 영역 정리를 완료하세요.")
        )
        val message=messages[settingDialog]
        AlertDialog(onDismissRequest={settingDialog=""},title={Text(if(settingDialog=="currency")"표시 통화" else message?.first?:"안내")},
            text={if(settingDialog=="currency")Column {
                listOf("KRW" to "대한민국 원 · KRW","USD" to "미국 달러 · USD").forEach { (id,label)->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=currency==id,onClick={currency=id;WalletStore.saveCurrency(context,id);settingDialog=""});Text(label)}
                }
                Text("원화·달러 환산은 시장 시세를 기준으로 한 참고 금액입니다.",fontSize=12.sp)
            } else Text(message?.second.orEmpty())},confirmButton={TextButton(onClick={settingDialog=""}){Text("확인")}})
    }
    val dates=cardMarks.associateWith { WalletStore.cardDate(context,it) }
    WalletDesign(WalletVisualState(
        address=address,balance=balance,tab=tab,page=page,to=if(page=="Confirm")prepared?.let{KaspaAddress.fromPublicKey(it.tx.outputs[0].script.copyOfRange(1,33))}?:sendTo else sendTo,amount=if(page=="Confirm")prepared?.let{java.math.BigDecimal.valueOf(it.tx.outputs[0].amount,8).toPlainString()}?:sendAmount else sendAmount,fee=displayedFee,
        txid=resultTx,sentAt=sentAt,cards=cardMarks,dates=dates,history=history,historyLoading=historyLoading,historyError=historyError,historyMore=historyMore,status=status,sendInfo=sendInfo,
        busy=busy||cardPreparing,refreshing=refreshing,awaiting=awaitingSendCard,recovery=recoveryMode,target=target?.name,
        unlocked=secret!=null,captureAllowed=captureAllowed,password=pass,passwordConfirm=passConfirm,newPassword=newPass,newPasswordConfirm=newPassConfirm,passwordFrozen=provisioningPass!=null,recoveryAddress=recoveryAddress,backupVerified=backupVerified,pending=WalletStore.pending(context)!=null,cleaning=cleaning,resumingRegistration=resumingRegistration,resumeAvailable=RegistrationResume.available(WalletStore.generation(context),cardMarks,recoveryVerified),recoveryVerified=recoveryVerified,cleanedCards=cleanedCards,addressValid=runCatching{KaspaAddress.recipientPayload(sendTo)}.isSuccess,relayFloor=prepared?.relayFloor?:false,confirmationOpen=overwritePrompt!=null,currency=currency,krw=quote?.krw,usd=quote?.usd,change=quote?.change
    ),DesignActions(
        navigate={if(!busy&&!awaitingSendCard&&target==null&&!recoveryMode&&!cleaning&&!resumingRegistration&&!cardPreparing){tab=it;page="";if(it=="Transactions")refreshHistory()}},
        route={next->if(!busy&&!awaitingSendCard){
            if((next=="Send"||next=="Receive")&&(cardMarks.size<3||!backupVerified))settingDialog="backup"
            else {page=next;if(next.isBlank()){prepared=null;resultTx="";sendInfo=""};if(next=="Send"){resultTx="";sendInfo=""}}
        }},
        resetPending={abandonDialog=true;abandonAccepted=false;abandonText=""},
        retryPending={val pending=WalletStore.pending(context);if(pending!=null&&!busy){busy=true;scope.launch{
            try{when(KaspaBroadcast.resolveOrRetry(pending.wallet,pending.txid,pending.request)) {
                KaspaBroadcast.Resolution.ACCEPTED->{WalletStore.clearPending(context,pending.txid);status="기존 거래의 네트워크 승인 확인 완료";refreshHistory()}
                KaspaBroadcast.Resolution.CONFLICTED->{WalletStore.clearPending(context,pending.txid);status="원래 입력을 사용한 다른 승인 거래 확인 · 기존 거래는 실행될 수 없습니다";refreshHistory()}
                KaspaBroadcast.Resolution.UNKNOWN->{status="거래 결과가 아직 불확실합니다. 새 송금 잠금을 유지합니다"}
            }}catch(e:Exception){status="기존 거래 확인 실패 · 복구 사본으로 재시도하거나 위험 확인 후 기록 잠금을 해제할 수 있습니다"}
            finally{busy=false}
        }}},historyRefresh={refreshHistory()},historyNext={refreshHistory(true)},copy={copy(it)},refresh={refresh();scope.launch{quote=runCatching{MarketQuote.fetch()}.getOrNull()}},
        scan={if(cardMarks.size<3||!backupVerified)settingDialog="backup" else launchScan()},changeTo={sendTo=it.trim();prepared=null;displayedFee=null;sendAll=false;inputRevision++},
        changeAmount={sendAmount=it;prepared=null;displayedFee=null;sendAll=false;inputRevision++},
        preflight={preflight()},
        send={if(prepared==null){page="Send";sendInfo="수수료를 다시 확인하세요"}else{awaitingSendCard=true;status="송금 인증 1/2 · 등록된 NFC 카드를 태그하세요";sendInfo=status}},
        cancel={cancelOperation()},
        max={if(sendTo.isBlank())sendInfo="전액 입력 전에 받는 주소를 입력하세요" else {
            val revision=inputRevision;val requestedFrom=address;val requestedTo=sendTo
            busy=true;sendInfo="전액 전송 수수료 계산 중…"
            scope.launch{try{val p=KaspaTransaction.prepareMaximum(requestedFrom,requestedTo);check(revision==inputRevision&&address==requestedFrom&&sendTo==requestedTo){"입력이 변경되었습니다"};sendAmount=java.math.BigDecimal.valueOf(p.tx.outputs.first().amount,8).stripTrailingZeros().toPlainString();sendAll=true;displayedFee=p.fee;prepared=p;sendInfo="수수료를 제외한 최대 수량입니다"}catch(e:Exception){sendInfo="계산 실패 · ${e.message}"}finally{busy=false}}
        }},
        resumeRegistration={if(!busy&&secret==null&&pass.length>=8&&RegistrationResume.available(WalletStore.generation(context),cardMarks,recoveryVerified)){resumingRegistration=true;status="이번 세대에 등록한 카드 1장을 태그한 뒤 지문 인증하세요. 등록에 사용한 비밀번호가 필요합니다"}},
        cleanup={if(!busy&&pass.isNotBlank()&&WalletStore.recoveryVerified(context)){cleaning=true;status="새 비밀번호로 현재 세대 A/B/C 카드를 하나씩 태그해 이전 영역을 지웁니다"}},
        restartProvision={if(secret!=null&&!busy){provisioningPass?.fill('\u0000');provisioningPass=null;WalletStore.beginProvision(context);cardMarks=emptySet();recoveryVerified=false;backupVerified=false;cleanedCards=emptySet();status="새 등록 세대를 시작했습니다. 새 비밀번호를 입력하세요. 이전 카드와 비밀번호는 복구 완료까지 보관하세요"}},
        generatePassword={if(!busy&&provisioningPass==null){val generated=PasswordPolicy.generate();if(address.isBlank()){pass=generated;passConfirm=""}else{newPass=generated;newPassConfirm=""};showGenerated=true}},password={if(!busy&&provisioningPass==null)pass=it},passwordConfirm={passConfirm=it},newPassword={if(provisioningPass==null)newPass=it},newPasswordConfirm={if(provisioningPass==null)newPassConfirm=it},recoveryAddress={recoveryAddress=it},
        create={if(BuildConfig.DEBUG){status="디버그 빌드에서는 실제 메인넷 지갑을 만들 수 없습니다"}else if(PasswordPolicy.valid(pass)&&pass==passConfirm){val key=KaspaAddress.newPrivateKey();try{saveWallet(key);shares=Shares.split(key);provisioningPass=pass.toCharArray();WalletStore.beginProvision(context);cardMarks=emptySet();backupVerified=false;recoveryVerified=false;cleanedCards=emptySet();status="지갑 생성 완료 · 카드 A/B/C 등록 후 복구 검증하세요";tab="Cards"}catch(e:Exception){status="처리 실패 · ${e.message}"}finally{key.fill(0)}}else status="처리 실패 · 비밀번호 강도와 재입력을 확인하세요"},
        recover={keepRecoveryKey=cardMarks.size<3;foundGeneration=null;recoveryMode=true;found.values.forEach{it.fill(0)};found=emptyMap();foundSecure=emptySet();recoveryPub=null;status="서로 다른 등록 카드 2장을 태그하세요"},unlock={keepRecoveryKey=true;foundGeneration=null;recoveryMode=true;found.values.forEach{it.fill(0)};found=emptyMap();foundSecure=emptySet();recoveryPub=null;status="카드 교체를 위해 카드 2장으로 잠금을 해제하세요"},register={prepareCard(it)},
        explorer={id->if(Regex("[0-9a-fA-F]{64}").matches(id))runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("${KaspaNetwork.EXPLORER}/txs/$id"))) }},
        setting={id->when(id){
            "capture"->{captureAllowed=!captureAllowed;status=if(captureAllowed)"문제 확인용 화면 캡처 허용 · 앱을 벗어나면 다시 차단됩니다" else "화면 캡처를 차단했습니다"}
            "locknow"->{lockSecrets();status="개인키와 비밀번호를 잠갔습니다"}
            "paste"->{val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;val candidate=cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty();sendTo=candidate;if(runCatching{KaspaAddress.recipientPayload(candidate)}.isFailure)sendInfo="주소 형식·체크섬·공개키를 확인하세요";prepared=null;displayedFee=null;sendAll=false;inputRevision++}
            "recovery"->{if(cardMarks.size<3||!backupVerified)settingDialog="backup" else {secret?.fill(0);secret=null;shares.values.forEach{it.fill(0)};shares=emptyMap();tab="Cards";status="복구 비밀번호를 입력하고 카드 2장으로 복구를 검증하세요"}}
            "discard"->discardUnbacked=true
            else->settingDialog=id
        }}
    ))
}

