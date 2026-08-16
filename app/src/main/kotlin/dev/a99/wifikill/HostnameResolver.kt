package dev.a99.wifikill

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HostnameResolver {

    suspend fun resolve(ip: String): String? = withTimeoutOrNull(3000) {
        coroutineScope {
            val strategies = listOf(
                async { strategyReverseDns(ip) },
                async { strategyMdns(ip) },
                async { strategyNetbios(ip) },
            )
            // Strategies run in parallel; each bounds itself with its own
            // timeout. Return the first non-null result, preferring the order
            // reverse DNS -> mDNS -> NetBIOS.
            strategies.firstNotNullOfOrNull { it.await() }
        }
    }

    private suspend fun strategyReverseDns(ip: String): String? = try {
        withTimeout(2000) {
            val host = InetAddress.getByName(ip).canonicalHostName
            host.takeIf { it != ip }
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun strategyMdns(ip: String): String? = try {
        withTimeout(1800) {
            val name = reversePtrName(ip)
            val query = buildPtrQuery(name)
            val socket = DatagramSocket()
            try {
                socket.soTimeout = 1000
                socket.send(
                    DatagramPacket(
                        query, query.size,
                        InetAddress.getByName("224.0.0.251"), 5353
                    )
                )
                val buf = ByteArray(4096)
                socket.receive(DatagramPacket(buf, buf.size))
                parsePtrAnswer(buf)
            } finally {
                socket.close()
            }
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun strategyNetbios(ip: String): String? = try {
        withTimeout(1500) {
            val s = DatagramSocket()
            try {
                s.soTimeout = 1000
                val q = buildNbnsQuery()
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
                val buf = ByteArray(512)
                s.receive(DatagramPacket(buf, buf.size))
                parseNbnsName(buf)
            } finally {
                s.close()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun reversePtrName(ip: String): StringBuilder {
        val parts = ip.split(".")
        return StringBuilder()
            .append(parts[3]).append('.').append(parts[2]).append('.')
            .append(parts[1]).append('.').append(parts[0])
            .append(".in-addr.arpa")
    }

    private fun encodeDnsName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split(".")) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun buildPtrQuery(reverseName: StringBuilder): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.putShort(0x0000) // tx id
        header.putShort(0x0000) // flags: standard query
        header.putShort(0x0001) // qdcount
        header.putShort(0x0000) // ancount
        header.putShort(0x0000) // nscount
        header.putShort(0x0000) // arcount
        out.write(header.array())
        encodeDnsName(out, reverseName.toString())
        out.write(0x00) // qtype PTR
        out.write(0x0c)
        out.write(0x00) // qclass IN
        out.write(0x01)
        return out.toByteArray()
    }

    private fun parsePtrAnswer(buf: ByteArray): String? {
        if (buf.size < 12) return null
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        val ancount = bb.getShort(6)
        if (ancount < 1) return null

        val qdcount = bb.getShort(4)
        var pos = 12
        for (i in 0 until qdcount) {
            pos = skipName(buf, pos) ?: return null
            pos += 4
        }
        for (i in 0 until ancount) {
            pos = skipName(buf, pos) ?: return null
            if (pos + 10 > buf.size) return null
            val type = bb.getShort(pos)
            val rdlength = bb.getShort(pos + 8)
            val rdata = pos + 10
            if (type == 12.toShort() && rdata + rdlength <= buf.size) {
                return decodeName(buf, rdata)
            }
            pos = rdata + rdlength
        }
        return null
    }

    private fun skipName(buf: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < buf.size) {
            val len = buf[pos].toInt() and 0xff
            if (len == 0) return pos + 1
            if ((len and 0xc0) == 0xc0) return pos + 2
            pos += 1 + len
        }
        return null
    }

    private fun decodeName(buf: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var pos = start
        var jumped = false
        var guard = 0
        var labelCount = 0
        while (pos < buf.size && guard++ < 100) {
            val len = buf[pos].toInt() and 0xff
            if (len == 0) {
                if (jumped) break
                return sb.toString().trimEnd('.')
            }
            if ((len and 0xc0) == 0xc0) {
                val offset = ((len and 0x3f) shl 8) or (buf[pos + 1].toInt() and 0xff)
                if (!jumped) {
                    jumped = true
                    pos = offset
                } else {
                    return decodeName(buf, offset)
                }
                continue
            }
            pos++
            if (pos + len > buf.size) return null
            if (labelCount++ > 0) sb.append('.')
            sb.append(String(buf, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return null
    }

    private fun buildNbnsQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.putShort(0x0030) // tx id
        header.putShort(0x0010) // flags: query
        header.putShort(0x0001) // qdcount
        header.putShort(0x0000)
        header.putShort(0x0000)
        header.putShort(0x0000)
        out.write(header.array())

        val name = "*".toByteArray(Charsets.US_ASCII)
        val encoded = ByteArray(32)
        for (i in name.indices) {
            encoded[i * 2] = ('A'.code + (name[i].toInt() shr 4)).toByte()
            encoded[i * 2 + 1] = ('A'.code + (name[i].toInt() and 0x0f)).toByte()
        }
        encoded[31] = 'A'.code.toByte()
        out.write(encoded)

        out.write(0x00) // qtype: NB
        out.write(0x20)
        out.write(0x00) // qclass: IN
        out.write(0x01)
        return out.toByteArray()
    }

    private fun parseNbnsName(buf: ByteArray): String? {
        if (buf.size < 56) return null
        // Response bit: high bit of the flags field (bytes 2-3).
        if ((buf[2].toInt() and 0x80) == 0) return null
        // ancount is big-endian 16-bit at bytes 6-7.
        val answerCount = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
        if (answerCount < 1) return null
        val nameBytes = buf.copyOfRange(12, 44)
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < nameBytes.size && nameBytes[i].toInt() != 0 && i < 30) {
            val hi = nameBytes[i].toInt()
            val lo = nameBytes[i + 1].toInt()
            if (hi < 0x41 || hi > 0x5a || lo < 0x41 || lo > 0x5a) break
            sb.append((((hi - 0x41) shl 4) or (lo - 0x41)).toChar())
            i += 2
        }
        // NBNS names are 0-15 chars; strip the space pad and the wired 0x00 pad.
        val name = sb.toString().substring(0, minOf(sb.length, 15)).trimEnd('*', ' ')
        return name.ifEmpty { null }
    }
}