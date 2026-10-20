package com.nfckaspa.wallet

/** Recovery remains 2-of-3; registration verification authenticates all three shares and every pair. */
internal object ThreeCardVerification {
    fun validate(shares:Map<Slot,ByteArray>,address:String,pub:ByteArray?) {
        require(shares.keys==Slot.entries.toSet()){"A/B/C 세 장이 모두 필요합니다"}
        shares.forEach{(slot,share)->require(share.size==33&&share[0].toInt()==slot.n){"슬롯과 복구 조각이 다릅니다"}}
        val slots=Slot.entries
        for(i in 0..1)for(j in i+1..2) {
            val key=Shares.join(shares.getValue(slots[i]),shares.getValue(slots[j]))
            try{RecoveryIdentity.validate(key,address,pub)}finally{key.fill(0)}
        }
    }
}
