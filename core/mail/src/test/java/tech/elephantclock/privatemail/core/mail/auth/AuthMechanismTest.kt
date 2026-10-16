package tech.elephantclock.privatemail.core.mail.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.auth.AuthMechanism.Companion.selectForPassword

class AuthMechanismTest {
    private fun imap(line: String) = selectForPassword(line.split(' '), imap = true)

    private fun smtp(mechs: String) = selectForPassword(mechs.split(' ').map { "AUTH=$it" }, imap = false)

    @Test
    fun `IMAP picks the strongest password mechanism after TLS`() {
        assertEquals(AuthMechanism.PLAIN, imap("IMAP4rev1 SASL-IR AUTH=PLAIN AUTH=LOGIN AUTH=XOAUTH2"))
        assertEquals(AuthMechanism.LOGIN, imap("IMAP4rev1 AUTH=LOGIN AUTH=XOAUTH2"))
        assertEquals(AuthMechanism.IMAP_LOGIN_COMMAND, imap("IMAP4rev1 LITERAL+ UIDPLUS IDLE MOVE AUTH=XOAUTH2"))
        assertEquals(null, imap("IMAP4rev1 LOGINDISABLED AUTH=XOAUTH2"))
        assertEquals(null, imap("IMAP4rev1 LOGINDISABLED AUTH=CRAM-MD5"))
    }

    @Test
    fun `SMTP never falls back to XOAUTH2 or CRAM-MD5 for a password`() {
        assertEquals(AuthMechanism.PLAIN, smtp("LOGIN PLAIN XOAUTH2"))
        assertEquals(AuthMechanism.LOGIN, smtp("LOGIN XOAUTH2"))
        assertEquals(null, smtp("XOAUTH2 OAUTHBEARER"))
        assertEquals(null, smtp("CRAM-MD5"))
    }

    @Test
    fun `Angus is offered PLAIN then LOGIN only`() {
        assertEquals("PLAIN LOGIN", AuthMechanism.PASSWORD_MECHANISMS)
    }
}
