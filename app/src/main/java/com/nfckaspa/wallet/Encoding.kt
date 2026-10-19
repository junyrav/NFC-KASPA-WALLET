package com.nfckaspa.wallet

internal fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
internal fun String.hexBytes():ByteArray {require(length==32&&matches(Regex("[0-9a-f]+")));return ByteArray(16){substring(it*2,it*2+2).toInt(16).toByte()}}
