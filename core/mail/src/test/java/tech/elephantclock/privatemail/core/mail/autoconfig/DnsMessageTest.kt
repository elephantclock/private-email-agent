package tech.elephantclock.privatemail.core.mail.autoconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsTestMessages.mx
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsTestMessages.response
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsTestMessages.srv

class DnsMessageTest {
    @Test
    fun `MX answers sorted by preference`() {
        val msg = response("example.com", 15, listOf(mx(20, "mx2.example.com"), mx(10, "MX1.Example.com")))
        assertEquals(listOf(MxRecord(10, "mx1.example.com"), MxRecord(20, "mx2.example.com")), DnsMessage.parseMx(msg))
    }

    @Test
    fun `compressed names in record data`() {
        // MX host "mail" + pointer to "example.com" in the question (offset 12).
        val rdata = byteArrayOf(0, 5, 4) + "mail".toByteArray() + byteArrayOf(0xC0.toByte(), 12)
        val msg = response("example.com", 15, listOf(DnsTestMessages.rr(15, rdata)))
        assertEquals(listOf(MxRecord(5, "mail.example.com")), DnsMessage.parseMx(msg))
    }

    @Test
    fun `SRV sorted by priority then weight, and dot target kept for the caller to skip`() {
        val msg =
            response(
                "_imaps._tcp.example.com",
                33,
                listOf(srv(10, 5, 993, "b.example.com"), srv(0, 0, 0, "."), srv(10, 50, 993, "a.example.com")),
            )
        assertEquals(
            listOf(
                SrvRecord(0, 0, 0, "."),
                SrvRecord(10, 50, 993, "a.example.com"),
                SrvRecord(10, 5, 993, "b.example.com"),
            ),
            DnsMessage.parseSrv(msg),
        )
    }

    @Test
    fun `other record types are ignored`() {
        val msg =
            response("example.com", 15, listOf(DnsTestMessages.rr(1, byteArrayOf(1, 2, 3, 4)), mx(1, "mx.example.com")))
        assertEquals(1, DnsMessage.parseMx(msg).size)
    }

    @Test
    fun `malformed input never throws`() {
        val good = response("example.com", 15, listOf(mx(1, "mx.example.com")))
        for (cut in good.indices) assertTrue(DnsMessage.parseMx(good.copyOf(cut)).size <= 1)
        // Pointer loop: a name that points at itself.
        val loop = response("example.com", 15, listOf(DnsTestMessages.rr(15, byteArrayOf(0, 1, 0xC0.toByte(), 0))))
        val selfPointer =
            loop.copyOf().also {
                it[it.size - 2] = 0xC0.toByte()
                it[it.size - 1] = (it.size - 2).toByte()
            }
        assertEquals(emptyList<MxRecord>(), DnsMessage.parseMx(selfPointer))
        assertEquals(emptyList<MxRecord>(), DnsMessage.parseMx(ByteArray(0)))
        assertEquals(emptyList<MxRecord>(), DnsMessage.parseMx(ByteArray(64) { 0xFF.toByte() }))
    }

    @Test
    fun `error responses yield nothing`() {
        val nxdomain = response("nope.example", 15, listOf(mx(1, "mx.example.com")), rcode = 3)
        assertEquals(emptyList<MxRecord>(), DnsMessage.parseMx(nxdomain))
    }
}
