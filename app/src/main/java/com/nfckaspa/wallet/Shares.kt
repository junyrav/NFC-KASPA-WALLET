package com.nfckaspa.wallet

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

