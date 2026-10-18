package tech.elephantclock.privatemail.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.auth.AuthError

/** Every AuthError has its own plain-language headline; server text only ever goes to detail. */
@RunWith(Parameterized::class)
class AuthErrorMessagesTest(
    private val error: AuthError,
    private val expected: Int,
) {
    companion object {
        private const val SERVER_TEXT = "NO [ALERT] raw server words"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            listOf(
                arrayOf(AuthError.WrongCredentials, R.string.error_wrong_credentials),
                arrayOf(
                    AuthError.AppPasswordRequired("https://help.example/app-passwords"),
                    R.string.error_app_password_required,
                ),
                arrayOf(AuthError.AccountLockedOrRateLimited(SERVER_TEXT), R.string.error_account_locked),
                arrayOf(AuthError.HostNotFound("imap.example.org"), R.string.error_host_not_found),
                arrayOf(AuthError.PortBlockedOrTimeout("imap.example.org", 993), R.string.error_port_blocked),
                arrayOf(AuthError.TlsFailure(SERVER_TEXT), R.string.error_tls_failure),
                arrayOf(AuthError.StartTlsUnavailable, R.string.error_starttls_unavailable),
                arrayOf(
                    AuthError.CertificateUntrusted(cert("imap.example.org", 993)),
                    R.string.error_certificate_untrusted,
                ),
                arrayOf(AuthError.AuthMethodNotSupported(SERVER_TEXT), R.string.error_auth_method_not_supported),
                arrayOf(AuthError.Unknown(SERVER_TEXT), R.string.error_unknown),
                arrayOf(AuthError.PasswordUnavailable, R.string.error_password_unavailable),
            )
    }

    @Test
    fun hasItsOwnHeadline() {
        val e = error.explain()
        assertEquals(expected, e.headline.res)
        assertFalse(e.headline.args.any { it.toString().contains(SERVER_TEXT) })
    }

    @Test
    fun serverTextOnlyInDetail() {
        val e = error.explain()
        if (error.toString().contains(SERVER_TEXT)) assertEquals(SERVER_TEXT, e.detail)
    }

    @Test
    fun appPasswordCarriesItsHelpLink() {
        val e = error.explain()
        if (error is AuthError.AppPasswordRequired) assertEquals(error.helpUrl, e.helpUrl)
    }
}
