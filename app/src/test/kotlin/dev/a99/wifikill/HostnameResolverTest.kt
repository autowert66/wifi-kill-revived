package dev.a99.wifikill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HostnameResolverTest {

    private val parsePtr = HostnameResolver::class.java
        .getDeclaredMethod("parsePtrAnswer", ByteArray::class.java)
        .apply { isAccessible = true }

    private val parseNbns = HostnameResolver::class.java
        .getDeclaredMethod("parseNbnsName", ByteArray::class.java)
        .apply { isAccessible = true }

    private fun encodeDnsName(parts: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { part ->
            out.write(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun dnsResponse(ancount: Int, type: Int, rdata: ByteArray): ByteArray {
        val short = { v: Int -> v.toShort() }
        val bb = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(short(0x1234))
            .putShort(short(0x8400))
            .putShort(short(1))  // qdcount
            .putShort(short(ancount))
            .putShort(short(0))
            .putShort(short(0))
        // dummy question: example.com PTR IN
        bb.put(encodeDnsName(listOf("example", "com")))
        bb.putShort(short(12)).putShort(short(1))
        if (ancount > 0) {
            // answer with compressed owner name -> pointer to offset 12 (question name)
            bb.put(0xc0.toByte()).put(12.toByte())
            bb.putShort(short(type))
            bb.putShort(short(1))
            bb.putInt(0x1234)
            bb.putShort(short(rdata.size))
            bb.put(rdata)
        }
        return bb.array().copyOf(bb.position())
    }

    private fun nbnsName(name: String): ByteArray {
        val raw = (name + "*".repeat(15 - name.length)).toByteArray(Charsets.US_ASCII)
        val encoded = ByteArray(32)
        for (i in 0 until 15) {
            val hi = (raw[i].toInt() shr 4) and 0xf
            val lo = raw[i].toInt() and 0xf
            encoded[i * 2] = ('A' + hi).code.toByte()
            encoded[i * 2 + 1] = ('A' + lo).code.toByte()
        }
        encoded[30] = 'A'.code.toByte()  // last char always 'A' (0x00)
        encoded[31] = 'A'.code.toByte()
        return encoded
    }

    @Test
    fun parsePtrAnswer_readsCompressedName() {
        val rdata = encodeDnsName(listOf("myphone", "local"))
        val buf = dnsResponse(ancount = 1, type = 12, rdata = rdata)
        val name = parsePtr.invoke(HostnameResolver(), buf) as String?
        assertEquals("myphone.local", name)
    }

    @Test
    fun parsePtrAnswer_returnsNullWhenNoAnswer() {
        val buf = dnsResponse(ancount = 0, type = 12, rdata = byteArrayOf())
        assertNull(parsePtr.invoke(HostnameResolver(), buf))
    }

    @Test
    fun parsePtrAnswer_skipsNonPtrRecords() {
        val buf = dnsResponse(ancount = 1, type = 1, rdata = byteArrayOf(1, 2, 3, 4))
        assertNull(parsePtr.invoke(HostnameResolver(), buf))
    }

    @Test
    fun parseNbnsName_decodesQuestionName() {
        // NBNS response; question name (offset 12..44) is the encoded hostname.
        val encode = { v: Int -> (v.toShort()) }
        val encoded = nbnsName("ALPHA")
        val buf = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(encode(0x0030))
        buf.putShort(encode(0x8500)) // response, no error
        buf.putShort(encode(1))      // qdcount
        buf.putShort(encode(1))      // ancount
        buf.putShort(encode(0))
        buf.putShort(encode(0))
        buf.put(encoded)
        buf.putShort(encode(0x0020))
        buf.putShort(encode(0x0001)) // qclass IN
        buf.put(encoded)     // answer name
        buf.putShort(encode(0x0020))
        buf.putShort(encode(0x0001))
        buf.putInt(0)
        buf.putShort(encode(6))
        buf.put(byteArrayOf(4, 1, 2, 3, 4, 5))
        val out = parseNbns.invoke(HostnameResolver(), buf.array()) as String?
        assertEquals("ALPHA", out)
    }
}