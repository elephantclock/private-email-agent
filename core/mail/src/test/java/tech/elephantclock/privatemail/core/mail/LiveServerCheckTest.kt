package tech.elephantclock.privatemail.core.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.io.File

/**
 * T11 [USER] check against the user's real server. Skipped unless run with
 * `PEA_LIVE_CHECK=1 ./gradlew :core:mail:testDebugUnitTest --tests '*LiveServerCheck*'` on the Mac.
 *
 * Read-only: IMAP sign-in, CAPABILITY and LIST (no SELECT); SMTP EHLO, AUTH, QUIT (no MAIL FROM).
 * Settings come from the Mac watcher's config; the password from its Keychain entry, held in memory
 * only. The result (no password, no message data) goes to build/live-check.txt.
 */
class LiveServerCheckTest {
    @Test
    fun testConnectionAgainstRealServer() {
        assumeTrue(System.getProperty("pea.liveCheck") == "1")
        val config =
            Json
                .parseToJsonElement(
                    File(System.getProperty("user.home"), ".config/claude-email/config.json").readText(),
                ).jsonObject

        fun str(key: String) = config.getValue(key).jsonPrimitive.content
        val username = str("username")
        val imapPort = str("imap_port").toInt()
        val smtpPort = str("smtp_port").toInt()

        val result =
            ConnectionTester(CertificatePins.None).test(
                ServerConfig(
                    str("imap_host"),
                    imapPort,
                    if (imapPort ==
                        993
                    ) {
                        ConnectionSecurity.SSL_TLS
                    } else {
                        ConnectionSecurity.STARTTLS
                    },
                ),
                ServerConfig(
                    str("smtp_host"),
                    smtpPort,
                    if (smtpPort ==
                        465
                    ) {
                        ConnectionSecurity.SSL_TLS
                    } else {
                        ConnectionSecurity.STARTTLS
                    },
                ),
                MailCredentials(username, Password(keychainPassword(username))),
            )

        val report =
            buildString {
                appendLine("IMAP ${str("imap_host")}:$imapPort → ${summary(result.imap)}")
                appendLine("SMTP ${str("smtp_host")}:$smtpPort → ${summary(result.smtp)}")
            }
        File("build/live-check.txt").writeText(report)
        check(result.ok) { report }
    }

    private fun summary(result: MailResult<*>): String =
        when (result) {
            is MailResult.Failed -> {
                "FAILED ${result.error}"
            }

            is MailResult.Ok -> {
                when (val v = result.value) {
                    is tech.elephantclock.privatemail.core.mail.imap.ImapCheck -> {
                        "OK ${v.capabilities}; ${v.folders.size} folders; special-use: " +
                            v.folders.filter { it.specialUse != null }.joinToString { "${it.specialUse}=${it.path}" }
                    }

                    else -> {
                        "OK"
                    }
                }
            }
        }

    /** Same Keychain entry the Mac watcher uses; read straight into memory, never printed. */
    private fun keychainPassword(account: String): String {
        val process =
            ProcessBuilder("security", "find-generic-password", "-s", "claude-email", "-a", account, "-w")
                .redirectError(File("/dev/null"))
                .start()
        val secret =
            process.inputStream
                .bufferedReader()
                .readText()
                .trimEnd('\n')
        check(process.waitFor() == 0 && secret.isNotEmpty()) { "Keychain entry 'claude-email' not readable" }
        return secret
    }
}
