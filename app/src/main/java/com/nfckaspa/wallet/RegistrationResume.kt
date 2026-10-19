package com.nfckaspa.wallet

/** Initial provisioning only: the same phone's authenticated daily key AND password-opened share.
 * This is never a replacement for two-card recovery on another phone or a completed backup.
 */
internal object RegistrationResume {
    fun available(generation:String,registered:Set<String>,verified:Boolean)=
        generation.matches(Regex("[0-9a-f]{32}"))&&registered.size<3&&registered.all{it in setOf("A","B","C")}&&!verified

    fun validateRecord(record:CardRecord,generation:String,registered:Set<String>,verified:Boolean,address:String,owner:String?) {
        require(available(generation,registered,verified)){"초기 카드 등록 중단 상태에서만 이어갈 수 있습니다"}
        require(record.version==3&&record.generation.hex()==generation&&record.pub.contentEquals(KaspaAddress.mainnetPayload(address))){"현재 등록 세대의 카드를 태그하세요"}
        require(owner==null||owner==record.slot.name){"이 기기의 카드 등록 정보와 다릅니다"}
    }
    fun restore(record:CardRecord,key:ByteArray,share:ByteArray,generation:String,registered:Set<String>,verified:Boolean,address:String,owner:String?):Map<Slot,ByteArray> {
        validateRecord(record,generation,registered,verified,address,owner)
        require(share.size==33&&share[0].toInt()==record.slot.n){"복구 조각과 카드 슬롯이 다릅니다"}
        RecoveryIdentity.validate(key,address,record.pub)
        return Shares.restoreWithKey(key,share)
    }
}
