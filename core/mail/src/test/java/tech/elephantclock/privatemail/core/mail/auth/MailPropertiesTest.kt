package tech.elephantclock.privatemail.core.mail.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import tech.elephantclock.privatemail.core.model.ConnectionSecurity

/** The Angus properties are where mechanism choice, TLS policy and timeouts actually live. */
class MailPropertiesTest {
    private val factory = TlsSocketFactory(PinningTrustManager(CertificatePins.None, TestCert.LOCALHOST.trustedBy()))

    @Test
    fun `STARTTLS is required, never optional, and only password mechanisms are offered`() {
        val p = mailProperties("smtp", ConnectionSecurity.STARTTLS, factory, Timeouts(1_000, 2_000))
        assertEquals("true", p["mail.smtp.starttls.enable"])
        assertEquals("true", p["mail.smtp.starttls.required"])
        assertEquals("false", p["mail.smtp.ssl.enable"])
        assertEquals("PLAIN LOGIN", p["mail.smtp.auth.mechanisms"])
        assertEquals("true", p["mail.smtp.auth.xoauth2.disable"])
        assertEquals("true", p["mail.smtp.auth.ntlm.disable"])
        assertSame(factory, p["mail.smtp.ssl.socketFactory"])
        assertEquals("1000", p["mail.smtp.connectiontimeout"])
        assertEquals("2000", p["mail.smtp.timeout"])
        assertEquals("2000", p["mail.smtp.writetimeout"])
        assertEquals("false", p["mail.debug"])
        assertEquals("false", p["mail.debug.auth"])
    }

    @Test
    fun `implicit TLS`() {
        val p = mailProperties("imap", ConnectionSecurity.SSL_TLS, factory, Timeouts())
        assertEquals("true", p["mail.imap.ssl.enable"])
        assertEquals(null, p["mail.imap.starttls.enable"])
    }
}
