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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class MainActivity:FragmentActivity(){
 private var nfc:NfcAdapter?=null;private var onTag:((Tag)->Unit)?=null;private var bio:((Boolean,String)->Unit)?=null
 override fun onCreate(b:Bundle?){super.onCreate(b);nfc=NfcAdapter.getDefaultAdapter(this);setContent{App({onTag=it},{cb->bio=cb;auth()})}}
 private fun auth(){if(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){bio?.invoke(false,"강한 생체인증을 사용할 수 없습니다");return};val p=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(r:BiometricPrompt.AuthenticationResult){bio?.invoke(true,"생체인증 성공")};override fun onAuthenticationError(c:Int,s:CharSequence){bio?.invoke(false,"생체인증 취소/실패: $s")};override fun onAuthenticationFailed(){bio?.invoke(false,"생체인증 실패")}});p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("NFC KASPA WALLET").setSubtitle("보안 작업을 승인하세요").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("취소").build())}
 override fun onResume(){super.onResume();val i=Intent(this,javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);nfc?.enableForegroundDispatch(this,PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_MUTABLE),null,null)}
 override fun onPause(){nfc?.disableForegroundDispatch(this);super.onPause()}
 override fun onNewIntent(i:Intent){super.onNewIntent(i);val t=if(android.os.Build.VERSION.SDK_INT>=33)i.getParcelableExtra(NfcAdapter.EXTRA_TAG,Tag::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(NfcAdapter.EXTRA_TAG);if(t!=null)onTag?.invoke(t)}
}
private enum class Slot(val n:Int){A(1),B(2),C(3)}
private data class Rec(val slot:Slot,val daily:ByteArray,val recovery:ByteArray)
private object Crypto{
 private val rng=SecureRandom();private const val KS="AndroidKeyStore";private const val ALIAS="nkw_v05_daily"
 private fun dev():SecretKey{val s=KeyStore.getInstance(KS).apply{load(null)};(s.getKey(ALIAS,null) as? SecretKey)?.let{return it};val g=KeyGenerator.getInstance("AES",KS);g.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setUserAuthenticationRequired(true).setUserAuthenticationParameters(30,KeyProperties.AUTH_BIOMETRIC_STRONG).build());return g.generateKey()}
 fun random(n:Int)=ByteArray(n).also{rng.nextBytes(it)}
 fun dailyEnc(p:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,dev());return c.iv+c.doFinal(p)}
 fun passKey(pass:String,salt:ByteArray):SecretKey{val f=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");return SecretKeySpec(f.generateSecret(PBEKeySpec(pass.toCharArray(),salt,120000,256)).encoded,"AES")}
 fun passEnc(p:ByteArray,pass:String):ByteArray{val salt=random(16);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,passKey(pass,salt));return salt+c.iv+c.doFinal(p)}
 fun passDec(b:ByteArray,pass:String):ByteArray{val salt=b.copyOfRange(0,16);val iv=b.copyOfRange(16,28);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,passKey(pass,salt),GCMParameterSpec(128,iv));return c.doFinal(b.copyOfRange(28,b.size))}
}
private object Shares{
 private fun mul(a:Int,b:Int):Int{var x=a;var y=b;var r=0;repeat(8){if((y and 1)!=0)r=r xor x;val hi=x and 0x80;x=(x shl 1)and 255;if(hi!=0)x=x xor 0x1b;y=y shr 1};return r}
 private fun pow(a:Int,n:Int):Int{var r=1;var x=a;var e=n;while(e>0){if((e and 1)!=0)r=mul(r,x);x=mul(x,x);e=e shr 1};return r}
 private fun div(a:Int,b:Int)=mul(a,pow(b,254))
 fun split(secret:ByteArray):Map<Slot,ByteArray>{val slope=Crypto.random(secret.size);return Slot.entries.associateWith{s->ByteArray(secret.size+1).also{out->out[0]=s.n.toByte();for(i in secret.indices)out[i+1]=(secret[i].toInt() xor mul(slope[i].toInt()and 255,s.n)).toByte()}}}
 fun join(a:ByteArray,b:ByteArray):ByteArray{val x1=a[0].toInt()and 255;val x2=b[0].toInt()and 255;require(x1!=x2);return ByteArray(a.size-1){i->val y1=a[i+1].toInt()and 255;val y2=b[i+1].toInt()and 255;val m=div(y1 xor y2,x1 xor x2);(y1 xor mul(m,x1)).toByte()}}
}
private object TestnetAddress{
 private val alphabet="qpzry9x8gf2tvdw0s3jn54khce6mua7l".toCharArray()
 private fun convert8to5(data:ByteArray):IntArray{var acc=0;var bits=0;val out=ArrayList<Int>();for(b in data){acc=(acc shl 8) or (b.toInt()and 255);bits+=8;while(bits>=5){bits-=5;out.add((acc shr bits)and 31)}};if(bits>0)out.add((acc shl(5-bits))and31);return out.toIntArray()}
 private fun poly(values:IntArray):Long{var c=1L;for(v in values){val c0=c ushr 35;c=((c and 0x07ffffffffL) shl 5) xor v.toLong();if((c0 and 1L)!=0L)c=c xor 0x98f2bc8e61L;if((c0 and 2L)!=0L)c=c xor 0x79b76d99e2L;if((c0 and 4L)!=0L)c=c xor 0xf33e5fb3c4L;if((c0 and 8L)!=0L)c=c xor 0xae2eabe2a8L;if((c0 and 16L)!=0L)c=c xor 0x1e4f43e470L};return c xor 1L}
 private fun prefixExpand(p:String)=IntArray(p.length+1){i->if(i<p.length)p[i].code and 31 else 0}
 fun fromTestSecret(secret:ByteArray):String{val payload=MessageDigest.getInstance("SHA-256").digest(secret);val data=intArrayOf(0)+convert8to5(payload);val pre=prefixExpand("kaspatest");val all=IntArray(pre.size+data.size+8);pre.copyInto(all);data.copyInto(all,pre.size);val mod=poly(all);val chk=IntArray(8){i->((mod shr (5*(7-i)))and31).toInt()};return "kaspatest:"+(data+chk).joinToString(""){alphabet[it].toString()}}
}
private object Card{
 private val key=MifareClassic.KEY_DEFAULT;private val magic=byteArrayOf(0x4e,0x4b,0x57,0x34);private val blocks=listOf(4,5,6,8,9,10,12,13,14,16,17,18,20,21,22)
 fun write(t:Tag,s:Slot,d:ByteArray,r:ByteArray):String{val payload=ByteArray(240);magic.copyInto(payload);payload[4]=s.n.toByte();payload[5]=d.size.toByte();payload[6]=r.size.toByte();d.copyInto(payload,7);r.copyInto(payload,7+d.size);payload[239]=sum(payload,239);val m=MifareClassic.get(t)?:return "MIFARE Classic 카드가 아닙니다";return try{m.connect();for(sec in 1..5)if(!m.authenticateSectorWithKeyA(sec,key))return "Sector $sec 인증 실패";blocks.forEachIndexed{i,b->m.writeBlock(b,payload.copyOfRange(i*16,(i+1)*16))};"CARD ${s.name} 저장 완료 ✓"}catch(e:Exception){"NFC 오류: ${e.javaClass.simpleName}"}finally{try{m.close()}catch(_:Exception){}}}
 fun read(t:Tag):Rec?{val m=MifareClassic.get(t)?:return null;return try{m.connect();for(sec in 1..5)if(!m.authenticateSectorWithKeyA(sec,key))return null;val p=ByteArray(240);blocks.forEachIndexed{i,b->m.readBlock(b).copyInto(p,i*16)};if(!p.copyOfRange(0,4).contentEquals(magic)||p[239]!=sum(p,239))return null;val s=Slot.entries.firstOrNull{it.n==(p[4].toInt()and255)}?:return null;val dl=p[5].toInt()and255;val rl=p[6].toInt()and255;if(dl<1||rl<1||7+dl+rl>239)return null;Rec(s,p.copyOfRange(7,7+dl),p.copyOfRange(7+dl,7+dl+rl))}catch(_:Exception){null}finally{try{m.close()}catch(_:Exception){}}}
 private fun sum(d:ByteArray,n:Int):Byte{var v=0;for(i in 0 until n)v=v xor(d[i].toInt()and255);return v.toByte()}
}
@Composable private fun App(reg:(((Tag)->Unit)->Unit),bio:(((Boolean,String)->Unit)->Unit)){
 var status by remember{mutableStateOf("Recovery Password를 정하고 테스트 지갑을 생성하세요")};var pass by remember{mutableStateOf("")};var secret by remember{mutableStateOf<ByteArray?>(null)};var address by remember{mutableStateOf("")};var shares by remember{mutableStateOf<Map<Slot,ByteArray>>(emptyMap())};var daily by remember{mutableStateOf<ByteArray?>(null)};var target by remember{mutableStateOf<Slot?>(null)};var found by remember{mutableStateOf<Map<Slot,ByteArray>>(emptyMap())}
 fun prepare(s:Slot){if(secret==null||pass.length<8){status="먼저 테스트 지갑을 생성하세요";return};bio{ok,msg->if(ok){try{daily=Crypto.dailyEnc(secret!!);target=s;status="생체인증 완료 ✓ · CARD ${s.name}를 태그하세요"}catch(e:Exception){status="암호화 실패: ${e.javaClass.simpleName}"}}else status=msg}}
 LaunchedEffect(Unit){reg{tag->val t=target;if(t!=null&&daily!=null){val rec=Crypto.passEnc(shares[t]!!,pass);status=Card.write(tag,t,daily!!,rec);if(status.contains("완료")){target=null;daily=null}}else{val r=Card.read(tag);if(r==null)status="카드 읽기 실패" else try{val sh=Crypto.passDec(r.recovery,pass);found=found+(r.slot to sh);status="CARD ${r.slot.name} Share 확인 ✓ (${found.size}/2)";if(found.size>=2){val two=found.values.take(2);val recovered=Shares.join(two[0],two[1]);address=TestnetAddress.fromTestSecret(recovered);secret=recovered;status="2-of-3 복구 성공 ✓ · Testnet 주소 재생성"}}catch(_:Exception){status="비밀번호 오류 또는 카드 데이터 손상"}}}}
 MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("NFC KASPA WALLET",style=MaterialTheme.typography.headlineMedium);Text("TESTNET · v0.5 ADDRESS PROTOTYPE");Spacer(Modifier.height(16.dp));if(address.isNotEmpty())Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Kaspa Testnet Address",style=MaterialTheme.typography.titleMedium);Text(address)}};Spacer(Modifier.height(12.dp));OutlinedTextField(pass,{pass=it;found=emptyMap()},label={Text("Recovery Password (8자 이상)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));Button(onClick={if(pass.length<8)status="비밀번호는 8자 이상으로 해주세요" else{secret=Crypto.random(32);address=TestnetAddress.fromTestSecret(secret!!);shares=Shares.split(secret!!);found=emptyMap();status="Testnet 테스트 키 생성 ✓ · A/B/C를 등록하세요"}}){Text("Kaspa Testnet 지갑 생성")};Spacer(Modifier.height(16.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){Button(onClick={prepare(Slot.A)}){Text("A 등록")};Button(onClick={prepare(Slot.B)}){Text("B 등록")};Button(onClick={prepare(Slot.C)}){Text("C 등록")}};Spacer(Modifier.height(14.dp));Card(Modifier.fillMaxWidth()){Text(status,Modifier.padding(16.dp))};Spacer(Modifier.height(16.dp));Text("복구: 앱 재실행 → 같은 Password → 서로 다른 카드 2장 태그");Spacer(Modifier.weight(1f));Text("#14 · 주소/UI 검증용 · 실제 KAS 송금 금지")}}}}
