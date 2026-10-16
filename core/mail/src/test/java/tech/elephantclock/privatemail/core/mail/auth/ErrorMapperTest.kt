package tech.elephantclock.privatemail.core.mail.auth

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.mail.tls.UntrustedReason
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.reflect.KClass

/** Replies recorded from (or modelled on) real providers' failed logins. */
class ErrorMapperTest {
    private val cases: List<Pair<String, KClass<out AuthError>>> =
        listOf(
            // Gmail IMAP / SMTP
            "[ALERT] Application-specific password required: https://support.google.com/accounts/answer/185833 (Failure)"
                to AuthError.AppPasswordRequired::class,
            "534-5.7.9 Application-specific password required. Learn more at https://support.google.com/mail/?p=InvalidSecondFactor"
                to AuthError.AppPasswordRequired::class,
            "[ALERT] Please log in via your web browser: https://support.google.com/mail/accounts/answer/78754 (Failure)"
                to AuthError.AccountLockedOrRateLimited::class,
            "534-5.7.14 <https://accounts.google.com/signin/continue> Please log in via your web browser"
                to AuthError.AccountLockedOrRateLimited::class,
            "[AUTHENTICATIONFAILED] Invalid credentials (Failure)" to AuthError.WrongCredentials::class,
            "535-5.7.8 Username and Password not accepted." to AuthError.WrongCredentials::class,
            // iCloud, Yahoo
            "[AUTHENTICATIONFAILED] Authentication failed." to AuthError.WrongCredentials::class,
            "[AUTHENTICATIONFAILED] LOGIN Invalid credentials" to AuthError.WrongCredentials::class,
            "[ALERT] Please verify your account by going to https://login.yahoo.com"
                to AuthError.AccountLockedOrRateLimited::class,
            // Microsoft
            "AUTHENTICATE failed." to AuthError.WrongCredentials::class,
            "535 5.7.139 Authentication unsuccessful, basic authentication is disabled."
                to AuthError.AuthMethodNotSupported::class,
            "535 5.7.3 Authentication unsuccessful" to AuthError.WrongCredentials::class,
            // Dovecot, Postfix, generic
            "[UNAVAILABLE] Temporary authentication failure." to AuthError.AccountLockedOrRateLimited::class,
            "[ALERT] Too many login failures" to AuthError.AccountLockedOrRateLimited::class,
            "454 4.7.0 Too many login attempts, please try again later." to AuthError.AccountLockedOrRateLimited::class,
            "535 5.7.0 Error: authentication failed: authentication failure" to AuthError.WrongCredentials::class,
            "No authentication mechanisms supported by both server and client"
                to AuthError.AuthMethodNotSupported::class,
        )

    @Test
    fun `recorded server replies map to the right error`() {
        val wrong =
            cases
                .filterNot { (reply, expected) -> expected.isInstance(ErrorMapper.fromServerReply(reply)) }
                .map { (reply, expected) ->
                    "$reply → ${ErrorMapper.fromServerReply(reply)} (expected ${expected.simpleName})"
                }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `app password reply keeps the provider's help link`() {
        val e = ErrorMapper.fromServerReply(cases[0].first) as AuthError.AppPasswordRequired
        assertEquals("https://support.google.com/accounts/answer/185833", e.helpUrl)
    }

    private fun map(e: Throwable) = ErrorMapper.map(e, "mail.example.com", 993, Password("secret-pw"), null)

    @Test
    fun `network and TLS exceptions`() {
        assertEquals(
            AuthError.HostNotFound("mail.example.com"),
            map(MessagingException("x", UnknownHostException("mail.example.com"))),
        )
        assertEquals(
            AuthError.PortBlockedOrTimeout("mail.example.com", 993),
            map(MessagingException("x", ConnectException("refused"))),
        )
        assertEquals(
            AuthError.PortBlockedOrTimeout("mail.example.com", 993),
            map(MessagingException("x", SocketTimeoutException("read"))),
        )
        assertTrue(map(MessagingException("x", SSLHandshakeException("bad"))) is AuthError.TlsFailure)
        assertEquals(
            AuthError.StartTlsUnavailable,
            map(MessagingException("STARTTLS is required but host does not support STARTTLS")),
        )
        assertEquals(
            AuthError.WrongCredentials,
            map(AuthenticationFailedException("[AUTHENTICATIONFAILED] Invalid credentials")),
        )
    }

    @Test
    fun `a rejected certificate wins over the exception that carried it`() {
        val cert = CertificateInfo("h", 993, "AA:BB", "CN=h", "CN=h", 0, UntrustedReason.CHAIN)
        assertEquals(
            AuthError.CertificateUntrusted(cert),
            ErrorMapper.map(MessagingException("x", SSLHandshakeException("PKIX")), "h", 993, null, cert),
        )
    }

    @Test
    fun `password is scrubbed from any text that is kept`() {
        val error = map(MessagingException("server said secret-pw is wrong"))
        assertEquals(AuthError.Unknown("server said ██ is wrong"), error)
    }
}
