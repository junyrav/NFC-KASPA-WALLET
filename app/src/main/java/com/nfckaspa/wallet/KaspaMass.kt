package com.nfckaspa.wallet

/** Kaspa v0 P2PK mass; integer KIP-9 storage, compute and normalized transient components. */
internal object KaspaMass {
    data class Parts(val compute:Long,val storage:Long,val transient:Long){val total get()=maxOf(compute,storage,transient);val relay get()=maxOf(compute,transient)}
    fun parts(tx:KaspaTx.Tx):Parts {
        require(tx.version==0&&tx.inputs.isNotEmpty()&&tx.outputs.isNotEmpty())
        require(tx.inputs.all{it.utxo.amount>0&&it.utxo.script.size==34&&it.utxo.scriptVersion==0&&it.sigOpCount==1})
        require(tx.outputs.all{it.amount>0&&it.script.size==34&&it.scriptVersion==0})
        // Fee preparation must account for the 66-byte signature even before signing.
        val size=94L+tx.inputs.size*118L+tx.outputs.size*52L
        val compute=size+tx.outputs.size*360L+tx.inputs.size*1000L
        val c=1_000_000_000_000L
        val positive=tx.outputs.fold(0L){n,o->Math.addExact(n,c/o.amount)}
        val n=tx.inputs.size.toLong()
        val negative=if(tx.outputs.size==1 || (tx.inputs.size<=2 && (tx.inputs.size==1||tx.outputs.size==2)))
            tx.inputs.fold(0L){sum,i->Math.addExact(sum,c/i.utxo.amount)}
        else Math.multiplyExact(n,c/(tx.inputs.fold(0L){sum,i->Math.addExact(sum,i.utxo.amount)}/n).coerceAtLeast(1))
        return Parts(compute,(positive-negative).coerceAtLeast(0),size*2)
    }
    fun calculate(tx:KaspaTx.Tx)=parts(tx).total
}
internal object FeePolicy {
    const val MIN_RATE=100.0 // Current mainnet relay floor, sompi/gram (Toccata).
    const val MAX_RATE=1000.0 // Ten times the relay floor; total/relative caps remain mandatory.
    const val MAX_FEE=1_000_000L // 0.01 KAS, additionally limited to 1% (with a 0.0001 KAS floor).
    fun rate(rate:Double){require(rate.isFinite()&&rate in MIN_RATE..MAX_RATE){"네트워크 수수료율 $rate sompi/gram이 허용 범위($MIN_RATE–$MAX_RATE)를 벗어났습니다. 서명하지 않았습니다"}}
    fun check(fee:Long,amount:Long,mass:Long) {
        require(fee>0&&amount>0&&fee<=MAX_FEE&&fee<=maxOf(10_000L,amount/100)&&fee<=Math.multiplyExact(mass,MAX_RATE.toLong())) {"수수료가 보안 상한을 초과했습니다. 송금을 중단했습니다"}
    }
}
