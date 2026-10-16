package tech.elephantclock.privatemail.core.mail.auth

/**
 * Password sign-in mechanisms. Angus Mail makes the actual choice: it takes the first
 * entry of [PASSWORD_MECHANISMS] that the server offers after TLS, and for IMAP falls
 * back to the `LOGIN` command when no `AUTH=` entry matches (unless `LOGINDISABLED`).
 * XOAUTH2 and NTLM are disabled in [mailProperties]; XOAUTH2 is the phase-7 extension
 * point. CRAM-MD5 is not implemented by Angus Mail, so CRAM-only servers fail with
 * [AuthError.AuthMethodNotSupported].
 */
object AuthMechanism {
    /** Strongest first. Both send the password, so they are only ever used inside TLS. */
    const val PASSWORD_MECHANISMS = "PLAIN LOGIN"
}
