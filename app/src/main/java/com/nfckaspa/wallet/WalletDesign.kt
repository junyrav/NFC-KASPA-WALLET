package com.nfckaspa.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Deep=Color(0xFF064D4C)
private val Teal=Color(0xFF007E78)
private val Aqua=Color(0xFF28CBB6)
private val Mint=Color(0xFFE5F5F2)
private val Ink=Color(0xFF102B32)
private val Muted=Color(0xFF607A80)
private val Line=Color(0xFFDDEBE9)
private val Green=Color(0xFF09B88D)
private val Corner=RoundedCornerShape(18.dp)

internal data class WalletVisualState(
    val address:String="", val balance:String="— KAS", val tab:String="Wallet", val page:String="",
    val to:String="",val amount:String="",val fee:Long?=null,val txid:String="",val sentAt:Long=0,
    val cards:Set<String> = emptySet(),val dates:Map<String,String> = emptyMap(),
    val history:List<WalletStore.Transfer> = emptyList(),
    val historyLoading:Boolean=false,val historyError:String="",val historyMore:Boolean=false,val status:String="",val sendInfo:String="",
    val busy:Boolean=false,val refreshing:Boolean=false,val awaiting:Boolean=false,val recovery:Boolean=false,
    val target:String?=null,val unlocked:Boolean=false,val password:String="",val currency:String="KRW",
    val passwordConfirm:String="",val newPassword:String="",val newPasswordConfirm:String="",val passwordFrozen:Boolean=false,
    val recoveryAddress:String="",val backupVerified:Boolean=false,val recoveryVerified:Boolean=false,val cleaning:Boolean=false,val cleanedCards:Set<String> = emptySet(),val addressValid:Boolean=false,val relayFloor:Boolean=false,val pending:Boolean=false,val confirmationOpen:Boolean=false,
    val krw:Double?=null,val usd:Double?=null,val change:Double?=null
)
internal data class DesignActions(
    val resetPending:()->Unit={},val cleanup:()->Unit={},val restartProvision:()->Unit={},val generatePassword:()->Unit={},val retryPending:()->Unit={},val unlock:()->Unit={},val passwordConfirm:(String)->Unit={},val newPassword:(String)->Unit={},val newPasswordConfirm:(String)->Unit={},val recoveryAddress:(String)->Unit={},
    val historyRefresh:()->Unit={},val historyNext:()->Unit={},
    val navigate:(String)->Unit={},val route:(String)->Unit={},val copy:(String)->Unit={},
    val refresh:()->Unit={},val scan:()->Unit={},val changeTo:(String)->Unit={},val changeAmount:(String)->Unit={},
    val preflight:()->Unit={},val send:()->Unit={},val cancel:()->Unit={},val max:()->Unit={},
    val password:(String)->Unit={},val create:()->Unit={},val recover:()->Unit={},val register:(Slot)->Unit={},
    val explorer:(String)->Unit={},val setting:(String)->Unit={}
)

internal fun kasNumber(value:String):String = value.removeSuffix(" KAS").trim()
internal fun kasFee(value:Long?):String = value?.let{BigDecimal.valueOf(it,8).stripTrailingZeros().toPlainString()}?:"—"
internal fun fiat(amount:String,s:WalletVisualState):String {
    val number=amount.toBigDecimalOrNull()?:return "≈ ${if(s.currency=="USD")"$" else "₩"} —"
    val rate=if(s.currency=="USD")s.usd else s.krw
    if(rate==null)return "≈ ${if(s.currency=="USD")"$" else "₩"} —"
    return if(s.currency=="USD")String.format(Locale.US,"≈ $%,.2f",number.toDouble()*rate)
        else String.format(Locale.KOREA,"≈ ₩%,.0f",number.toDouble()*rate)
}
private fun total(s:WalletVisualState):String = runCatching {
    s.amount.toBigDecimal().add(BigDecimal.valueOf(s.fee?:0,8)).stripTrailingZeros().toPlainString()
}.getOrDefault("—")

@Composable internal fun KaspaBrand(modifier:Modifier=Modifier) {
    Image(
        painter=painterResource(R.drawable.kaspa_nfc_logo),
        contentDescription="Kaspa NFC Wallet 로고",
        contentScale=ContentScale.Fit,
        modifier=modifier.clip(CircleShape)
    )
}

@Composable private fun WhiteCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=Corner,color=MaterialTheme.colorScheme.surface,
        shadowElevation=2.dp,border=androidx.compose.foundation.BorderStroke(1.dp,Line.copy(alpha=.65f))) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable private fun Primary(label:String,onClick:()->Unit,enabled:Boolean=true,icon:ImageVector?=null) {
    val shape=RoundedCornerShape(28.dp)
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),shape=shape,
        contentPadding=PaddingValues(0.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,disabledContainerColor=Color(0xFFD9E3E2)),
        elevation=ButtonDefaults.buttonElevation(0.dp)) {
        Box(Modifier.fillMaxWidth().background(if(enabled)Brush.linearGradient(listOf(Color(0xFF08A595),Deep)) else Brush.linearGradient(listOf(Color.Transparent,Color.Transparent)),shape).padding(vertical=16.dp),contentAlignment=Alignment.Center) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(icon!=null)Icon(icon,null,Modifier.size(20.dp))
                Text(label,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
            }
        }
    }
}
@Composable private fun Title(text:String,back:()->Unit,close:Boolean=false,enabled:Boolean=true) {
    Box(Modifier.fillMaxWidth().height(54.dp)) {
        Text(text,Modifier.align(Alignment.Center),fontSize=20.sp,fontWeight=FontWeight.Bold,color=Ink)
        IconButton(onClick=back,enabled=enabled,modifier=Modifier.align(if(close)Alignment.CenterEnd else Alignment.CenterStart)) {
            Icon(if(close)Icons.Outlined.Close else Icons.Outlined.ArrowBack,"돌아가기",tint=Ink)
        }
    }
}
@Composable private fun ValueRow(label:String,value:String,sub:String?=null,bold:Boolean=false) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
        Text(label,Modifier.weight(1f),fontSize=14.sp,color=Muted)
        Column(horizontalAlignment=Alignment.End,modifier=Modifier.weight(1.3f)) {
            Text(value,fontSize=if(bold)17.sp else 14.sp,fontWeight=if(bold)FontWeight.SemiBold else FontWeight.Medium,color=Ink)
            if(sub!=null)Text(sub,fontSize=12.sp,color=Muted)
        }
    }
}
@Composable private fun Hint(text:String,detail:String="",icon:ImageVector=Icons.Outlined.Shield) {
    Row(Modifier.fillMaxWidth().background(Mint,Corner).padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,Modifier.size(26.dp),tint=Teal)
        Column {Text(text,fontSize=13.sp,color=Ink,fontWeight=FontWeight.Medium);if(detail.isNotBlank())Text(detail,fontSize=11.sp,color=Muted)}
    }
}

@Composable internal fun OverwriteConsentDialog(description:String,accepted:Boolean,onAccepted:(Boolean)->Unit,onContinue:()->Unit,onCancel:()->Unit) {
    AlertDialog(onDismissRequest=onCancel,title={Text("기존 카드 데이터 덮어쓰기")},
        text={Column{Text(description);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(accepted,onAccepted);Text("기존 지갑 복구에 미치는 영향을 확인했습니다")}}},
        confirmButton={TextButton(enabled=accepted,onClick=onContinue){Text("덮어쓰기")}},
        dismissButton={TextButton(onClick=onCancel){Text("취소")}})
}

@Composable internal fun WalletDesign(s:WalletVisualState,a:DesignActions) {
    val home=s.address.isNotBlank()&&s.tab=="Wallet"&&s.page.isBlank()
    if(!s.confirmationOpen&&(s.awaiting||s.recovery||s.target!=null||s.cleaning))AlertDialog(
        onDismissRequest={if(!s.busy)a.cancel()},
        title={Text(if(s.target!=null)"${s.target} 카드 등록" else if(s.recovery)"2-of-3 지갑 복구" else if(s.cleaning)"이전 카드 데이터 정리" else "NFC 카드 인증")},
        text={Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Nfc,null,Modifier.size(52.dp),tint=Teal)
            Text(s.status,fontSize=14.sp)
            if(s.recovery||s.cleaning)OutlinedTextField(s.password,a.password,label={Text("태그할 카드의 복구 비밀번호")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!s.busy,singleLine=true)
            if(s.busy){LinearProgressIndicator(Modifier.fillMaxWidth(),color=Teal);Text("카드를 움직이지 마세요 · 최대 5회 자동 재시도",fontSize=12.sp,color=Muted)}
            else Text("휴대폰의 NFC 위치에 카드를 가까이 대세요.",fontSize=12.sp,color=Muted)
        }},
        confirmButton={TextButton(onClick=a.cancel,enabled=!s.busy){Text("대기 취소")}}
    )
    val scroll=remember(s.tab,s.page){ScrollState(0)}
    val activity=LocalActivity.current
    val view=LocalView.current
    SideEffect { activity?.let {
        WindowInsetsControllerCompat(it.window,view).isAppearanceLightStatusBars=!home
        @Suppress("DEPRECATION")
        if(android.os.Build.VERSION.SDK_INT<35)it.window.statusBarColor=(if(home)Deep else Color(0xFFF8FCFB)).toArgb()
    } }
    Scaffold(containerColor=if(home)Deep else Color(0xFFF1F9F7),bottomBar={
        if(s.page!="Success") NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,modifier=Modifier.border(.5.dp,Line)) {
            listOf(Triple("Wallet","지갑",Icons.Outlined.Home),Triple("Transactions","거래내역",Icons.Outlined.FormatListBulleted),Triple("Cards","카드관리",Icons.Outlined.CreditCard),Triple("Settings","설정",Icons.Outlined.Settings)).forEach { (id,label,icon) ->
                NavigationBarItem(selected=s.tab==id,onClick={a.navigate(id)},enabled=!s.busy&&!s.awaiting&&!s.recovery&&s.target==null,
                    icon={Icon(icon,null,Modifier.size(23.dp))},label={Text(label,fontSize=11.sp)},
                    colors=NavigationBarItemDefaults.colors(selectedIconColor=Teal,selectedTextColor=Teal,indicatorColor=Mint,unselectedIconColor=Muted,unselectedTextColor=Muted))
            }
        }
    }) { padding ->
        val background=if(home)Brush.verticalGradient(listOf(Deep,Color(0xFF098C7F),Color(0xFFDDF6F0),Color(0xFFF1F9F7)),endY=1300f)
            else Brush.verticalGradient(listOf(Color(0xFFF8FCFB),Color(0xFFECF8F5)))
        Column(Modifier.padding(padding).fillMaxSize().background(background).verticalScroll(scroll).padding(horizontal=18.dp).padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(if(s.tab=="Settings")5.dp else if(home)12.dp else 10.dp)) {
            if(s.address.isBlank()) {
                Spacer(Modifier.height(24.dp));KaspaBrand(Modifier.size(64.dp).align(Alignment.CenterHorizontally))
                Text("Kaspa NFC Wallet",Modifier.align(Alignment.CenterHorizontally),fontWeight=FontWeight.Bold,fontSize=23.sp,color=Ink)
                Spacer(Modifier.height(10.dp))
                WhiteCard {Text("지갑 시작하기",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("새 지갑을 만들거나 NFC 카드 2장으로 기존 지갑을 복구하세요.",color=Muted)
                    Hint("Kaspa 메인넷 지갑", "실제 KAS를 사용하는 지갑입니다. 복구 비밀번호와 NFC 카드를 안전하게 보관하세요.")
                    OutlinedTextField(s.password,a.password,label={Text("복구 비밀번호 · 새 지갑은 16자 이상")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!s.busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
                    OutlinedTextField(s.passwordConfirm,a.passwordConfirm,label={Text("새 지갑 비밀번호 재입력")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!s.busy&&!s.recovery,modifier=Modifier.fillMaxWidth())
                    Text("새 비밀번호: 16자 이상, 대·소문자·숫자·기호 중 3종류",fontSize=12.sp,color=Muted)
                    TextButton(onClick=a.generatePassword,enabled=!s.busy&&!s.recovery){Text("강한 비밀번호 생성")}
                    Primary("새 메인넷 지갑 생성",a.create,PasswordPolicy.valid(s.password)&&s.password==s.passwordConfirm&&!s.busy&&!s.recovery)
                    OutlinedTextField(s.recoveryAddress,a.recoveryAddress,label={Text("기존 지갑 주소 · 구버전 카드 복구 시 필수")},enabled=!s.busy&&!s.recovery,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(onClick=a.recover,enabled=s.password.length>=8&&!s.busy&&!s.recovery,modifier=Modifier.fillMaxWidth().height(50.dp)){Text("기존 카드로 복구")}
                }
                Hint("Your Keys on NFC","Secure · Simple · Yours")
            } else if(home) HomeDesign(s,a)
            else if(s.tab=="Wallet") when(s.page) {
                "Send"->SendDesign(s,a)
                "Confirm"->ConfirmDesign(s,a)
                "Success"->SuccessDesign(s,a)
                "Receive"->ReceiveDesign(s,a)
            } else when(s.tab) {
                "Settings"->SettingsDesign(s,a)
                "Cards"->CardsDesign(s,a)
                "Transactions"->TransactionsDesign(s,a)
            }
            if(s.busy||s.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Teal,trackColor=Mint)
            if(s.status.startsWith("처리 실패")||s.status.startsWith("잔액 조회 실패")||s.awaiting||s.recovery||s.target!=null||s.cleaning||s.tab=="Cards"||s.tab=="Transactions") {
                Hint(s.status,if(s.busy)"카드를 움직이지 마세요 · 최대 5회 자동 재시도" else "")
                if((s.awaiting||s.recovery||s.target!=null||s.cleaning)&&!s.busy) TextButton(onClick=a.cancel,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("대기 취소")}
            }
        }
    }
}

@Composable private fun ColumnScope.HomeDesign(s:WalletVisualState,a:DesignActions) {
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        KaspaBrand(Modifier.size(46.dp))
        Column(Modifier.weight(1f)){Text("Kaspa NFC Wallet",fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=Color.White);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){Text("Mainnet",color=Color(0xFFD0EEE8),fontSize=13.sp);Box(Modifier.size(7.dp).background(Aqua,CircleShape))}}
        IconButton(onClick={a.navigate("Settings")}){Icon(Icons.Outlined.Settings,"설정 열기",tint=Color.White)}
    }
    Surface(color=Color(0xFF003E3D).copy(alpha=.58f),shape=Corner,border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF47B7A6).copy(alpha=.7f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("내 지갑 잔액",Modifier.weight(1f),color=Color.White,fontWeight=FontWeight.Medium);IconButton(onClick=a.refresh,modifier=Modifier.size(24.dp)){Icon(Icons.Outlined.Refresh,"잔액 새로고침",tint=Aqua,modifier=Modifier.size(20.dp))}}
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(runCatching{java.text.DecimalFormat("#,##0.00000000",java.text.DecimalFormatSymbols(Locale.US)).format(kasNumber(s.balance).toBigDecimal())}.getOrDefault(kasNumber(s.balance)),Modifier.weight(1f,fill=false),fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("KAS",fontSize=18.sp,color=Color.White,modifier=Modifier.padding(bottom=3.dp))
            }
            Text(fiat(kasNumber(s.balance),s),fontSize=21.sp,fontWeight=FontWeight.Medium,color=Color.White)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                val rate=if(s.currency=="USD")s.usd else s.krw
                Text(if(rate==null)"참고 시세 연결 중" else if(s.currency=="USD")String.format(Locale.US,"1 KAS = $%.4f",rate) else String.format(Locale.KOREA,"1 KAS = ₩%,.2f",rate),fontSize=12.sp,color=Color(0xFFDCF5EE))
                s.change?.let{Text(String.format(Locale.US,"%+.2f%% (24h)",it),fontSize=12.sp,color=if(it>=0)Aqua else Color(0xFFFFC7BA))}
            }
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(Triple("보내기",Icons.Outlined.NorthEast,{a.route("Send")}),Triple("받기",Icons.Outlined.South,{a.route("Receive")}),Triple("QR 스캔",Icons.Outlined.QrCodeScanner,a.scan),Triple("거래내역",Icons.Outlined.ReceiptLong,{a.navigate("Transactions")})).forEachIndexed { i,(label,icon,click) ->
            val enabled=i==3||(s.cards.size==3&&s.backupVerified)
            Column(Modifier.weight(1f).height(84.dp).background(Brush.linearGradient(if(i==1)listOf(Color(0xFF08BF9D),Teal) else listOf(Color(0xFF169F93),Deep)),RoundedCornerShape(16.dp)).clickable(enabled=enabled,onClick=click).padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Icon(icon,label,Modifier.size(25.dp),tint=if(enabled)Color.White else Color.White.copy(alpha=.4f))
                Text(label,color=if(enabled)Color.White else Color.White.copy(alpha=.4f),fontSize=12.sp)
            }
        }
    }
    if(!s.backupVerified)Hint("백업 미완료", "카드관리에서 보안 카드 3장 등록·2장 복구 검증·3장 이전 영역 정리를 마친 뒤 입금하세요.")
    WhiteCard {
        Text("내 주소 (kaspa)",fontWeight=FontWeight.SemiBold,fontSize=13.sp,color=Ink)
        Row(Modifier.fillMaxWidth().background(Color(0xFFF6FAF9),RoundedCornerShape(8.dp)),verticalAlignment=Alignment.CenterVertically){Text(shortKaspaAddress(s.address),Modifier.weight(1f).padding(start=8.dp),fontSize=13.sp,maxLines=1);IconButton(onClick={a.copy(s.address)}){Icon(Icons.Outlined.ContentCopy,"주소 복사",Modifier.size(19.dp),tint=Ink)};IconButton(onClick={a.route("Receive")}){Icon(Icons.Outlined.QrCode2,"수신 QR 보기",Modifier.size(22.dp),tint=Ink)}}
    }
    WhiteCard {
        Text("NFC 카드 상태",fontWeight=FontWeight.SemiBold,fontSize=14.sp,color=Ink)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Slot.entries.forEach { slot ->
                Column(Modifier.weight(1f).border(1.dp,Line,RoundedCornerShape(12.dp)).clickable{a.navigate("Cards")}.padding(vertical=12.dp,horizontal=3.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("${slot.name} 카드",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Teal)
                    Icon(if(slot.name in s.cards)Icons.Outlined.CheckCircle else Icons.Outlined.CreditCard,null,tint=if(slot.name in s.cards)Green else Muted,modifier=Modifier.size(26.dp))
                    Text(if(slot.name in s.cards)"등록됨" else "미등록",fontSize=12.sp,color=Muted)
                    Text(s.dates[slot.name]?:"—",fontSize=10.sp,color=Muted)
                }
            }
        }
    }
    Hint("NFC 카드로 안전하게 자산을 관리하세요.","2-of-3 복구 지원 · NFC + 생체인증")
    Text("Mainnet · 원화/달러는 시장 시세 참고 환산",fontSize=10.sp,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally))
}

@Composable private fun SendDesign(s:WalletVisualState,a:DesignActions) {
    Title("KAS 보내기",{a.route("")},enabled=!s.busy)
    val validAddress=runCatching{KaspaAddress.recipientPayload(s.to)}.isSuccess
    Text("받는 주소 (kaspa)",fontSize=13.sp,color=Ink)
    if(s.to.isNotBlank())Text(if(validAddress)"주소 형식·공개키 검증 완료 · 수신자 소유 여부는 별도 확인하세요" else "주소 형식·체크섬·공개키를 확인하세요",fontSize=12.sp,color=if(validAddress)Teal else MaterialTheme.colorScheme.error)
    OutlinedTextField(s.to,a.changeTo,modifier=Modifier.fillMaxWidth(),placeholder={Text("kaspa: 주소를 입력하세요",fontSize=13.sp)},shape=RoundedCornerShape(14.dp),textStyle=LocalTextStyle.current.copy(fontSize=13.sp),enabled=!s.busy,
        trailingIcon={Row{IconButton(onClick=a.scan){Icon(Icons.Outlined.QrCodeScanner,"수신 주소 스캔",Modifier.size(20.dp))};IconButton(onClick={a.setting("paste")}){Icon(Icons.Outlined.ContentPaste,"주소 붙여넣기",Modifier.size(20.dp))}}},colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Line,unfocusedContainerColor=Color.White))
    Spacer(Modifier.height(5.dp));Text("보낼 수량 (KAS)",fontSize=14.sp,fontWeight=FontWeight.Medium,color=Ink)
    OutlinedTextField(s.amount,a.changeAmount,modifier=Modifier.fillMaxWidth(),placeholder={Text("0",fontSize=34.sp,fontWeight=FontWeight.Bold)},textStyle=LocalTextStyle.current.copy(fontSize=34.sp,fontWeight=FontWeight.Bold),suffix={Text("KAS",color=Teal)},singleLine=true,enabled=!s.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Line,unfocusedContainerColor=Color.White))
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(fiat(s.amount,s),Modifier.weight(1f),fontSize=15.sp,color=Muted);TextButton(onClick=a.max,enabled=!s.busy&&validAddress){Text("전액 입력",fontSize=12.sp)}}
    Text("보유 잔액: ${s.balance}",fontSize=12.sp,color=Muted)
    Spacer(Modifier.height(2.dp))
    WhiteCard {
        ValueRow("수수료 (예상)","${kasFee(s.fee)} KAS",if(s.fee!=null)fiat(kasFee(s.fee),s) else "다음 단계에서 네트워크 계산")
        HorizontalDivider(color=Line)
        ValueRow("총 전송 금액",if(s.fee==null)"— KAS" else "${total(s)} KAS",if(s.fee==null)null else fiat(total(s),s),true)
    }
    if(s.sendInfo.isNotBlank())Text(s.sendInfo,fontSize=12.sp,color=Muted)
    Spacer(Modifier.height(6.dp));Primary(if(s.busy)"수수료 계산 중…" else "다음",a.preflight,!s.busy&&validAddress&&runCatching{KaspaTransaction.kasToSompi(s.amount)}.isSuccess)
}

@Composable private fun ConfirmDesign(s:WalletVisualState,a:DesignActions) {
    Title("송금 확인",{a.route("Send")},enabled=!s.busy&&!s.awaiting)
    WhiteCard {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("받는 주소",Modifier.weight(1f),fontSize=14.sp,color=Muted);Text("주소는 아래 전체 표시",fontSize=12.sp,color=Muted);IconButton(onClick={a.copy(s.to)}){Icon(Icons.Outlined.ContentCopy,"수신 주소 복사",Modifier.size(18.dp))}}
        Text(s.to,fontSize=14.sp,color=Ink)
        Text("전체 수신 주소를 확인하세요. 처음 보내는 주소라면 받는 사람과 별도로 대조하세요.",fontSize=12.sp,color=Muted)
        ValueRow("보낼 수량","${s.amount} KAS",fiat(s.amount,s),true)
        HorizontalDivider(color=Line);ValueRow("수수료 (예상)","${kasFee(s.fee)} KAS",fiat(kasFee(s.fee),s))
        HorizontalDivider(color=Line);ValueRow("총 전송 금액","${total(s)} KAS",fiat(total(s),s),true)
    }
    Spacer(Modifier.height(4.dp))
    if(s.relayFloor)Text("기본 중계 수수료 모드 · 혼잡 시 거래 포함이 늦어질 수 있습니다",fontSize=12.sp,color=Muted)
    Hint("NFC 카드를 태그하고","생체인증을 완료하면 송금이 진행됩니다.",Icons.Outlined.Info)
    Primary(if(s.awaiting)"NFC 카드를 태그하세요" else "1. NFC 카드 태그 / 생체인증",a.send,!s.busy&&!s.awaiting,Icons.Outlined.Nfc)
    Primary(if(s.busy)"2. 서명 및 전송 중…" else "2. 서명 및 네트워크 전송",{},false)
    if(s.sendInfo.isNotBlank())Text(s.sendInfo,fontSize=12.sp,color=Muted)
    Text("Mainnet · 제출 후 네트워크 승인을 확인하세요",fontSize=11.sp,color=Muted)
}

@Composable private fun ColumnScope.SuccessDesign(s:WalletVisualState,a:DesignActions) {
    Title("송금 완료",{a.route("")},close=true)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.align(Alignment.CenterHorizontally).size(86.dp).background(Brush.linearGradient(listOf(Color(0xFF09C697),Green)),CircleShape).padding(20.dp).then(Modifier),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Check,null,Modifier.size(50.dp),tint=Color.White)}
    Text("송금이 제출되었습니다.",Modifier.align(Alignment.CenterHorizontally),fontSize=21.sp,fontWeight=FontWeight.Bold,color=Green)
    WhiteCard {
        Text("TXID",fontSize=13.sp,color=Muted)
        Row(verticalAlignment=Alignment.CenterVertically){Text(s.txid,Modifier.weight(1f),fontSize=12.sp,color=Ink,maxLines=3);IconButton(onClick={a.copy(s.txid)}){Icon(Icons.Outlined.ContentCopy,"TXID 복사",Modifier.size(20.dp))}}
        OutlinedButton(onClick={a.explorer(s.txid)},modifier=Modifier.fillMaxWidth()){Text("Explorer에서 보기");Spacer(Modifier.width(6.dp));Icon(Icons.Outlined.OpenInNew,null,Modifier.size(16.dp))}
    }
    WhiteCard {
        ValueRow("보낸 금액","${s.amount} KAS",fiat(s.amount,s))
        ValueRow("수수료","${kasFee(s.fee)} KAS",fiat(kasFee(s.fee),s))
        HorizontalDivider(color=Line);ValueRow("총 전송 금액","${total(s)} KAS",fiat(total(s),s),true)
        if(s.sentAt>0)ValueRow("전송 일시",SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.KOREA).format(Date(s.sentAt)))
    }
    Text("네트워크 접수 완료 · 블록 확인은 Explorer에서 확인하세요.",fontSize=11.sp,color=Muted)
    Primary("확인",{a.route("")})
}

@Composable private fun ReceiveDesign(s:WalletVisualState,a:DesignActions) {
    Title("KAS 받기",{a.route("")})
    WhiteCard {
        Text("내 지갑 주소",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink)
        val bitmap=remember(s.address){qr(s.address)}
        Image(bitmap.asImageBitmap(),"메인넷 수신 주소 QR",Modifier.size(242.dp).align(Alignment.CenterHorizontally))
        Text(s.address,fontSize=13.sp,color=Ink)
        Primary("주소 복사",{a.copy(s.address)},icon=Icons.Outlined.ContentCopy)
    }
    Hint("Kaspa 메인넷 전용","kaspa: 주소로 메인넷 KAS만 보내세요.")
}

@Composable private fun SettingsGroup(label:String,content:@Composable ColumnScope.()->Unit) {
    Text(label,fontSize=13.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(start=3.dp,top=3.dp))
    Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=1.dp){Column(content=content)}
}
@Composable private fun SettingsRow(icon:ImageVector,label:String,value:String="",onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
        Icon(icon,null,Modifier.size(20.dp),tint=Ink);Text(label,Modifier.weight(1f),fontSize=14.sp,lineHeight=20.sp,color=Ink)
        if(value.isNotEmpty())Text(value,fontSize=11.sp,lineHeight=16.sp,color=if(value.startsWith("✓"))Green else Muted,maxLines=1)
        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(16.dp),tint=Muted)
    }
}
@Composable private fun SettingsDesign(s:WalletVisualState,a:DesignActions) {
    Title("설정",{a.navigate("Wallet")})
    SettingsGroup("표시 설정") {
        SettingsRow(Icons.Outlined.CurrencyExchange,"표시 통화",s.currency){a.setting("currency")}
        SettingsRow(Icons.Outlined.Language,"언어","한국어"){a.setting("language")}
        SettingsRow(Icons.Outlined.Palette,"테마","Kaspa Teal"){a.setting("theme")}
    }
    SettingsGroup("보안 설정") {
        SettingsRow(Icons.Outlined.Fingerprint,"생체인증","송금 시 필수"){a.setting("biometric")}
        SettingsRow(Icons.Outlined.Lock,"개인키 잠금","NFC + 생체인증"){a.setting("lock")}
    }
    SettingsGroup("NFC 카드 관리") {
        if(s.unlocked) {
            Text("새 카드용 비밀번호는 3장 등록을 마칠 때까지 유지됩니다.",fontSize=12.sp,color=Muted)
            if(!s.passwordFrozen) {
            TextButton(onClick=a.generatePassword,enabled=!s.busy){Text("강한 비밀번호 생성")}
            OutlinedTextField(s.newPassword,a.newPassword,label={Text("새 카드용 복구 비밀번호 · 16자 이상")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,enabled=!s.passwordFrozen&&!s.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(s.newPasswordConfirm,a.newPasswordConfirm,label={Text("새 카드용 비밀번호 재입력")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,enabled=!s.passwordFrozen&&!s.busy,modifier=Modifier.fillMaxWidth())
            } else Text("현재 등록에 설정한 비밀번호를 사용 중입니다.",fontSize=12.sp,color=Muted)
            TextButton(onClick={a.setting("locknow")}){Text("지금 잠그기")}
        }
        Text(if(s.backupVerified)"보안 카드 3장 · 복구 검증 완료" else "백업 미완료 · 보안 카드 3장 등록·2장 복구 검증·이전 영역 정리가 필요합니다",fontSize=12.sp,color=Teal)
        Slot.entries.forEach {slot->SettingsRow(Icons.Outlined.CreditCard,"${slot.name} 카드",if(slot.name in s.cards)"✓ 등록됨" else "미등록"){a.navigate("Cards")}}
        SettingsRow(Icons.Outlined.VerifiedUser,"카드 재등록 / 교체"){a.navigate("Cards")}
        SettingsRow(Icons.Outlined.AddModerator,"2-of-3 복구 테스트"){a.setting("recovery")}
    }
    SettingsGroup("네트워크") {
        SettingsRow(Icons.Outlined.Public,"네트워크","Kaspa Mainnet"){a.setting("network")}
        SettingsRow(Icons.Outlined.Dns,"노드","공식 API (기본값)"){a.setting("node")}
    }
    SettingsGroup("앱 정보") {
        SettingsRow(Icons.Outlined.Info,"버전",BuildConfig.VERSION_NAME){a.setting("version")}
        SettingsRow(Icons.Outlined.Description,"오픈소스 라이선스"){a.setting("licenses")}
        if(s.cards.isEmpty()&&!s.unlocked)SettingsRow(Icons.Outlined.RestartAlt,"미등록 지갑 닫기"){a.setting("discard")}
    }
}

@Composable private fun CardsDesign(s:WalletVisualState,a:DesignActions) {
    Title("카드 관리",{a.navigate("Wallet")})
    Hint("카드 하드웨어 주의","MIFARE Classic은 복제·변조 방지 카드가 아닙니다. 강한 복구 비밀번호와 카드 분리 보관이 필요하며, 과거에 복사된 암호문은 카드 정리로 회수할 수 없습니다.")
    Hint("2-of-3 복구 지원","서로 다른 카드 2장과 복구 비밀번호로 복구합니다.",Icons.Outlined.Nfc)
    WhiteCard {
        Text(if(s.unlocked)"NFC 카드 등록 / 교체" else "등록 잠금 해제",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink)
        if(!s.unlocked) {
            Text("카드마다 해당 비밀번호를 입력하고 태그하세요. 이전 세대 카드도 복구는 가능하지만 현재 세대 검증과 정리는 별도로 필요합니다.",fontSize=13.sp,color=Muted)
            OutlinedTextField(s.password,a.password,label={Text("복구 비밀번호")},visualTransformation=PasswordVisualTransformation(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!s.busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
            Primary(if(s.backupVerified)"카드 2장으로 복구 재검증" else "카드 2장으로 복구 검증",a.recover,s.password.length>=8&&!s.busy&&!s.recovery)
            OutlinedButton(onClick=a.unlock,enabled=s.password.length>=8&&!s.busy&&!s.recovery){Text("카드 등록·교체를 위해 잠금 해제")}
        }
        if(s.unlocked) {
            Text("새 카드용 비밀번호는 3장 등록을 마칠 때까지 유지됩니다.",fontSize=12.sp,color=Muted)
            if(!s.passwordFrozen) {
            TextButton(onClick=a.generatePassword,enabled=!s.busy){Text("강한 비밀번호 생성")}
            OutlinedTextField(s.newPassword,a.newPassword,label={Text("새 카드용 복구 비밀번호 · 16자 이상")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,enabled=!s.passwordFrozen&&!s.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(s.newPasswordConfirm,a.newPasswordConfirm,label={Text("새 카드용 비밀번호 재입력")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),singleLine=true,enabled=!s.passwordFrozen&&!s.busy,modifier=Modifier.fillMaxWidth())
            } else Text("현재 등록에 설정한 비밀번호를 사용 중입니다.",fontSize=12.sp,color=Muted)
            TextButton(onClick={a.setting("locknow")}){Text("지금 잠그기")}
        }
        Text(if(s.backupVerified)"보안 카드 3장 · 복구 검증 완료" else "백업 미완료 · 보안 카드 3장 등록·2장 복구 검증·이전 영역 정리가 필요합니다",fontSize=12.sp,color=Teal)
        Slot.entries.forEach {slot->
            Row(Modifier.fillMaxWidth().border(1.dp,Line,RoundedCornerShape(12.dp)).padding(13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.CreditCard,null,tint=Teal,modifier=Modifier.size(28.dp))
                Column(Modifier.weight(1f)){Text("${slot.name} 카드",fontWeight=FontWeight.SemiBold,color=Ink);Text(if(slot.name in s.cards)"등록됨 · ${s.dates[slot.name]?:"등록 확인"}" else "미등록",fontSize=11.sp,color=Muted)}
                OutlinedButton(onClick={a.register(slot)},enabled=s.unlocked&&!s.busy&&!s.recovery&&s.target==null,contentPadding=PaddingValues(horizontal=12.dp)){Text(if(slot.name in s.cards)"재등록" else "등록",fontSize=12.sp)}
            }
        }
        if(s.unlocked)TextButton(onClick=a.restartProvision,enabled=!s.busy&&s.target==null){Text("등록 세대를 새로 시작")}
        if(s.recoveryVerified&&!s.backupVerified){
            Text("새 세대 복구 검증 완료 · 이전 데이터 정리 ${s.cleanedCards.size}/3",color=Teal)
            Text("새 복구 비밀번호를 입력한 후 A/B/C를 태그하세요. 현재 영역은 보존하고 이전 영역만 지웁니다.",fontSize=12.sp,color=Muted)
            if(s.unlocked)OutlinedTextField(s.password,a.password,label={Text("정리할 카드의 새 복구 비밀번호")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!s.busy,modifier=Modifier.fillMaxWidth())
            Primary("이전 데이터 정리",a.cleanup,s.password.isNotBlank()&&!s.busy&&!s.recovery&&!s.cleaning)
        }
        Text("등록 이력은 이 기기에서 확인한 기준입니다.",fontSize=11.sp,color=Muted)
    }
}

@Composable private fun TransactionsDesign(s:WalletVisualState,a:DesignActions) {
    Title("거래내역",{a.navigate("Wallet")})
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text("메인넷 입금 · 출금 내역",Modifier.weight(1f),fontSize=12.sp,color=Muted)
        TextButton(onClick=a.historyRefresh,enabled=!s.historyLoading){Text(if(s.historyLoading)"조회 중…" else "새로고침")}
    }
    if(s.historyError.isNotBlank())Text(s.historyError,fontSize=12.sp,color=Muted)
    if(s.pending)WhiteCard {
        Text("제출 결과 확인이 필요한 거래",fontWeight=FontWeight.Bold,color=Ink)
        Text("새 송금은 잠겨 있습니다. 아래 버튼은 기존에 서명한 동일 거래만 재확인·재전송합니다.",fontSize=12.sp,color=Muted)
        TextButton(onClick=a.retryPending,enabled=!s.busy){Text("기존 거래 확인 / 동일 거래 재전송")}
        TextButton(onClick=a.resetPending,enabled=!s.busy){Text("결과 확인 불가 / 기록 잠금 수동 해제")}
        Text("수동 해제는 거래 취소가 아니며 중복 지급 위험이 있습니다.",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    }
    val records=s.history.filter{it.wallet==s.address}
    if(records.isEmpty()&&!s.historyLoading&&s.historyError.isBlank())WhiteCard {
        Icon(Icons.Outlined.ReceiptLong,null,Modifier.size(42.dp).align(Alignment.CenterHorizontally),tint=Teal)
        Text("아직 거래내역이 없습니다",Modifier.align(Alignment.CenterHorizontally),fontSize=14.sp,color=Muted)
    }
    records.forEach {tx->WhiteCard {
        Row(verticalAlignment=Alignment.CenterVertically){Icon(if(tx.direction=="in")Icons.Outlined.SouthWest else Icons.Outlined.NorthEast,null,tint=Teal);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(when(tx.direction){"in"->"입금";"self"->"내 주소로 전송";"out"->"출금";else->"거래 확인 중"},fontWeight=FontWeight.SemiBold,color=Ink);Text(SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.KOREA).format(Date(tx.time)),fontSize=11.sp,color=Muted)};Text("${tx.amount} KAS",fontWeight=FontWeight.SemiBold,color=Ink)}
        Text(when(tx.state){"accepted"->"네트워크 승인";"submitted"->"제출됨 · 승인 확인 대기";"unaccepted"->"네트워크 미승인";else->"승인 상태 확인 중"},fontSize=11.sp,color=Muted)
        if(tx.to.isNotBlank())Text(shortKaspaAddress(tx.to),fontSize=12.sp,color=Muted)
        Row{TextButton(onClick={a.copy(tx.txid)}){Text("TXID 복사")};TextButton(onClick={a.explorer(tx.txid)}){Text("Explorer에서 보기")}}
    }}
    if(s.historyMore)TextButton(onClick=a.historyNext,enabled=!s.historyLoading){Text("이전 거래 더 보기")}
}
