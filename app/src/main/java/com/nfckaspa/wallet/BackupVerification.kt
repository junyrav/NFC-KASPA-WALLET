package com.nfckaspa.wallet

internal object BackupVerification {
    fun incompleteReason(registered:Set<String>,currentPair:Set<Slot>):String?=when {
        registered!=setOf("A","B","C")->"현재 등록 세대의 카드 등록이 ${registered.intersect(setOf("A","B","C")).size}/3입니다. A/B/C 등록을 먼저 마치세요"
        currentPair.size!=2->"현재 등록 세대 카드가 ${currentPair.size}/2장입니다. 이전 세대 카드로 키 복구는 가능하지만, 백업 검증은 현재 세대의 서로 다른 2장으로 해야 합니다"
        else->null
    }
}
