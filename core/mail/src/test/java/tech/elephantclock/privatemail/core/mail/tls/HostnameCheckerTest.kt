package tech.elephantclock.privatemail.core.mail.tls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.support.TestCert

class HostnameCheckerTest {
    @Test
    fun `wildcards cover exactly one label`() {
        assertTrue(HostnameChecker.dnsMatches("mail.example.com", "*.example.com"))
        assertFalse(HostnameChecker.dnsMatches("example.com", "*.example.com"))
        assertFalse(HostnameChecker.dnsMatches("a.b.example.com", "*.example.com"))
        assertFalse(HostnameChecker.dnsMatches("evil.com", "*.com"))
        assertTrue(HostnameChecker.dnsMatches("mail.example.com", "mail.example.com"))
    }

    @Test
    fun `subject alternative names, including IP literals`() {
        val cert = TestCert.LOCALHOST.certificate
        assertTrue(HostnameChecker.matches("localhost", cert))
        assertTrue(HostnameChecker.matches("LOCALHOST.", cert))
        assertTrue(HostnameChecker.matches("127.0.0.1", cert))
        assertFalse(HostnameChecker.matches("mail.example.com", cert))
        assertTrue(HostnameChecker.matches("mail.example.com", TestCert.OTHER_HOST.certificate))
    }
}
