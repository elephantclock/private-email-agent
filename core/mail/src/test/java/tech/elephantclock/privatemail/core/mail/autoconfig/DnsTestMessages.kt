package tech.elephantclock.privatemail.core.mail.autoconfig

import java.io.ByteArrayOutputStream

/** Builds DNS response messages for tests (one question, uncompressed unless noted). */
object DnsTestMessages {
    fun name(n: String): ByteArray =
        ByteArrayOutputStream()
            .apply {
                if (n != ".") {
                    n.trimEnd('.').split('.').forEach {
                        write(it.length)
                        write(it.toByteArray())
                    }
                }
                write(0)
            }.toByteArray()

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun response(
        question: String,
        type: Int,
        answers: List<ByteArray>,
        rcode: Int = 0,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write(u16(0x1234))
                write(u16(0x8180 or rcode))
                write(u16(1))
                write(u16(answers.size))
                write(u16(0))
                write(u16(0))
                write(name(question))
                write(u16(type))
                write(u16(1))
                answers.forEach { write(it) }
            }.toByteArray()

    /** One resource record: owner given as a pointer to the question name at offset 12. */
    fun rr(
        type: Int,
        rdata: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write(byteArrayOf(0xC0.toByte(), 12))
                write(u16(type))
                write(u16(1))
                write(byteArrayOf(0, 0, 0x0E, 0x10))
                write(u16(rdata.size))
                write(rdata)
            }.toByteArray()

    fun mx(
        pref: Int,
        host: String,
    ) = rr(DnsMessage.TYPE_MX, u16(pref) + name(host))

    fun srv(
        priority: Int,
        weight: Int,
        port: Int,
        target: String,
    ) = rr(DnsMessage.TYPE_SRV, u16(priority) + u16(weight) + u16(port) + name(target))
}
