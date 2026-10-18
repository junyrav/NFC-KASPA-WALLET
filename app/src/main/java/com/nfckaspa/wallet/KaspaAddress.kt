package com.nfckaspa.wallet

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.ec.CustomNamedCurves

object KaspaAddress {
    private val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l".toCharArray()
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val rng = SecureRandom()

    fun newPrivateKey(): ByteArray {
        while (true) {
            val b = ByteArray(32).also { rng.nextBytes(it) }
            val d = BigInteger(1, b)
            if (d.signum() > 0 && d < curve.n) return b
        }
    }

    fun xOnlyPublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)
        val d = BigInteger(1, privateKey)
        require(d.signum() > 0 && d < curve.n)
        val raw = curve.g.multiply(d).normalize().affineXCoord.encoded
        return if (raw.size == 32) raw else ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
    }

    fun testnet(privateKey: ByteArray): String {
        val x = xOnlyPublicKey(privateKey)
        val payload = ByteArray(33)
        payload[0] = 0
        x.copyInto(payload, 1)
        val data = convert8to5(payload)
        val prefix = prefixExpand("kaspatest")
        val values = IntArray(prefix.size + data.size + 8)
        prefix.copyInto(values)
        data.copyInto(values, prefix.size)
        val mod = polymod(values)
        val checksum = IntArray(8) { i -> ((mod shr (5 * (7 - i))) and 31).toInt() }
        return "kaspatest:" + (data + checksum).joinToString("") { alphabet[it].toString() }
    }

    /** Returns the 32-byte x-only public key payload from a kaspatest P2PK address. */
    fun testnetPayload(address: String): ByteArray {
        require(address.startsWith("kaspatest:")) { "TN10 address required" }
        val text = address.substringAfter(':').lowercase()
        require(text.length > 8) { "Invalid address" }
        val values = IntArray(text.length) { i -> alphabet.indexOf(text[i]).also { require(it >= 0) { "Invalid address character" } } }
        val expanded = prefixExpand("kaspatest")
        require(polymod(expanded + values) == 0L) { "Address checksum mismatch" }
        val payload = convert5to8(values.copyOfRange(0, values.size - 8))
        require(payload.size == 33 && payload[0].toInt() == 0) { "Only PubKey addresses are supported" }
        return payload.copyOfRange(1, 33)
    }

    private fun convert8to5(data: ByteArray): IntArray {
        var acc = 0; var bits = 0; val out = ArrayList<Int>()
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; out.add((acc shr bits) and 31); acc = acc and ((1 shl bits) - 1) }
        }
        if (bits > 0) out.add((acc shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    private fun convert5to8(data: IntArray): ByteArray {
        var acc = 0; var bits = 0; val out = ArrayList<Byte>()
        for (v in data) {
            require(v in 0..31)
            acc = (acc shl 5) or v; bits += 5
            while (bits >= 8) { bits -= 8; out.add(((acc shr bits) and 255).toByte()); acc = acc and ((1 shl bits) - 1) }
        }
        require(bits < 5 && (bits == 0 || (acc shl (8 - bits)) and 255 == 0)) { "Invalid address padding" }
        return out.toByteArray()
    }

    private fun prefixExpand(p: String) = IntArray(p.length + 1) { i -> if (i < p.length) p[i].code and 31 else 0 }

    private fun polymod(values: IntArray): Long {
        var c = 1L
        for (v in values) {
            val c0 = c ushr 35
            c = ((c and 0x07ffffffffL) shl 5) xor v.toLong()
            if ((c0 and 1L) != 0L) c = c xor 0x98f2bc8e61L
            if ((c0 and 2L) != 0L) c = c xor 0x79b76d99e2L
            if ((c0 and 4L) != 0L) c = c xor 0xf33e5fb3c4L
            if ((c0 and 8L) != 0L) c = c xor 0xae2eabe2a8L
            if ((c0 and 16L) != 0L) c = c xor 0x1e4f43e470L
        }
        return c xor 1L
    }
}
