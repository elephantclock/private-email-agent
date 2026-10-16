package tech.elephantclock.privatemail.core.mail.autoconfig

data class MxRecord(
    val preference: Int,
    val host: String,
)

data class SrvRecord(
    val priority: Int,
    val weight: Int,
    val port: Int,
    /** "." means the service is explicitly not offered (RFC 2782). */
    val target: String,
)

/**
 * Minimal parser for DNS response messages (RFC 1035) holding MX or SRV answers.
 * Answers come from the network, so every read is bounds-checked and name compression
 * is limited; malformed input yields an empty list, never an exception.
 */
object DnsMessage {
    const val TYPE_MX = 15
    const val TYPE_SRV = 33
    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_NAME_LENGTH = 255

    fun parseMx(message: ByteArray): List<MxRecord> =
        answers(message, TYPE_MX) { r, start ->
            MxRecord(r.u16(start), r.name(start + 2).first)
        }.sortedBy { it.preference }

    fun parseSrv(message: ByteArray): List<SrvRecord> =
        answers(message, TYPE_SRV) { r, start ->
            SrvRecord(r.u16(start), r.u16(start + 2), r.u16(start + 4), r.name(start + 6).first)
        }.sortedWith(compareBy<SrvRecord> { it.priority }.thenByDescending { it.weight })

    private fun <T> answers(
        message: ByteArray,
        type: Int,
        decode: (Reader, Int) -> T,
    ): List<T> =
        try {
            val r = Reader(message)
            val rcode = r.u16(2) and 0x000F
            if (rcode != 0) return emptyList()
            val questions = r.u16(4)
            val answers = r.u16(6)
            var pos = 12
            repeat(questions) { pos = r.name(pos).second + 4 }
            buildList {
                repeat(answers) {
                    pos = r.name(pos).second
                    val rrType = r.u16(pos)
                    val length = r.u16(pos + 8)
                    val data = pos + 10
                    r.check(data + length)
                    if (rrType == type) add(decode(r, data))
                    pos = data + length
                }
            }
        } catch (_: MalformedDnsException) {
            emptyList()
        }

    private class MalformedDnsException : Exception()

    private class Reader(
        val bytes: ByteArray,
    ) {
        fun check(end: Int) {
            if (end > bytes.size || end < 0) throw MalformedDnsException()
        }

        fun u8(at: Int): Int {
            check(at + 1)
            return bytes[at].toInt() and 0xFF
        }

        fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

        /** Returns the dotted name and the position just after it in the original record. */
        fun name(start: Int): Pair<String, Int> {
            val labels = mutableListOf<String>()
            var pos = start
            var end = -1
            var jumps = 0
            var length = 0
            while (true) {
                val len = u8(pos)
                when {
                    len == 0 -> {
                        if (end < 0) end = pos + 1
                        break
                    }

                    len and 0xC0 == 0xC0 -> {
                        if (++jumps > MAX_POINTER_JUMPS) throw MalformedDnsException()
                        if (end < 0) end = pos + 2
                        pos = ((len and 0x3F) shl 8) or u8(pos + 1)
                    }

                    len and 0xC0 != 0 -> {
                        throw MalformedDnsException()
                    }

                    else -> {
                        check(pos + 1 + len)
                        length += len + 1
                        if (length > MAX_NAME_LENGTH) throw MalformedDnsException()
                        labels += String(bytes, pos + 1, len, Charsets.US_ASCII)
                        pos += 1 + len
                    }
                }
            }
            return (if (labels.isEmpty()) "." else labels.joinToString(".").lowercase()) to end
        }
    }
}
