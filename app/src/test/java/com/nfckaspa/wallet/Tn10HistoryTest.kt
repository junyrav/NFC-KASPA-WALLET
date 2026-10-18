package com.nfckaspa.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class Tn10HistoryTest {
    private val wallet="kaspatest:wallet"
    private val other="kaspatest:sender"
    private fun output(owner:String,amount:Long)=JSONObject().put("script_public_key_address",owner).put("amount",amount)
    private fun input(owner:String,amount:Long)=JSONObject().put("previous_outpoint_resolved",output(owner,amount))
    private fun transaction(id:String,inputs:List<JSONObject>,outputs:List<JSONObject>,accepted:Boolean=true)=JSONObject()
        .put("transaction_id",id).put("block_time",1700000000000L).put("is_accepted",accepted)
        .put("inputs",JSONArray(inputs)).put("outputs",JSONArray(outputs))
    private fun parse(tx:JSONObject)=Tn10History.parse(wallet,JSONArray().put(tx).toString()).records.single()
    @Before fun clear() { RuntimeEnvironment.getApplication().getSharedPreferences("kaspa_nfc_wallet",0).edit().clear().commit() }
    @Test fun externalDepositIncludesAllWalletOutputsExactly() {
        val record=parse(transaction("deposit",listOf(input(other,100000010L)),listOf(output(wallet,10000000),output(wallet,90000000))))
        assertEquals("in",record.direction);assertEquals("1.00000000",record.amount)
        assertEquals("accepted",record.state);assertEquals(other,record.to);assertEquals(10L,record.fee)
    }
    @Test fun outgoingExcludesChangeAndFee() {
        val record=parse(transaction("sent",listOf(input(wallet,200000000L)),listOf(output(other,100000000L),output(wallet,99990000L))))
        assertEquals("out",record.direction);assertEquals("1.00000000",record.amount);assertEquals(10000L,record.fee)
    }
    @Test fun selfSendDoesNotBecomeAnExternalDeposit() {
        val record=parse(transaction("self",listOf(input(wallet,200000000L)),listOf(output(wallet,199990000L))))
        assertEquals("self",record.direction);assertEquals(10000L,record.fee)
    }
    @Test fun lightResolutionAndUnacceptedStatusAreSupported() {
        val record=parse(transaction("light",listOf(JSONObject().put("previous_outpoint_address",other).put("previous_outpoint_amount",200000000)),listOf(output(wallet,199990000)),false))
        assertEquals("in",record.direction);assertEquals("unaccepted",record.state)
    }
    @Test fun unresolvedInputsAreNotMislabelledAsDeposit() {
        val record=parse(transaction("unknown",listOf(JSONObject()),listOf(output(wallet,100000000))))
        assertEquals("unknown",record.direction);assertEquals(-1L,record.fee)
    }
    @Test fun fetchedRecordsReplaceLocalSubmissionWithoutDuplicatesAndPersist() {
        val c=RuntimeEnvironment.getApplication()
        WalletStore.record(c,wallet,"sent",other,"1",10000)
        val record=parse(transaction("sent",listOf(input(wallet,200000000L)),listOf(output(other,100000000L),output(wallet,99990000L))))
        WalletStore.mergeHistory(c,listOf(record))
        WalletStore.mergeHistory(c,listOf(record))
        val reloaded=c.createConfigurationContext(c.resources.configuration)
        assertEquals(listOf(record),WalletStore.history(reloaded))
        WalletStore.mergeHistory(c,emptyList())
        assertEquals(listOf(record),WalletStore.history(c))
    }
    @Test fun paginationCountsRawRecordsAndEmptyPage() {
        val tx=transaction("deposit",listOf(input(other,100000010)),listOf(output(wallet,100000000)))
        val page=Tn10History.parse(wallet,JSONArray().apply{repeat(50){put(JSONObject(tx.toString()).put("transaction_id","tx$it"))}}.toString())
        assertEquals(50,page.count);assertEquals(50,page.records.size)
        assertEquals(0,Tn10History.parse(wallet,"[]").count)
    }
}
