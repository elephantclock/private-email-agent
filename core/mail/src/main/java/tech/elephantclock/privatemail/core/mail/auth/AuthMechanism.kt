package tech.elephantclock.privatemail.core.mail.auth

/**
 * Login methods, strongest first. All of these send the password, so they are only
 * ever used inside TLS. XOAUTH2 is the phase-7 extension point and is never chosen
 * for password sign-in. CRAM-MD5 is not implemented by Angus Mail.
 */
enum class AuthMechanism(
    val angusName: String,
) {
    PLAIN("PLAIN"),
    LOGIN("LOGIN"),

    /** IMAP's own `LOGIN` command, for servers that list no `AUTH=` mechanism. */
    IMAP_LOGIN_COMMAND("LOGIN"),
    XOAUTH2("XOAUTH2"),
    ;

    companion object {
        /** Mechanisms offered to Angus for password sign-in, in preference order. */
        const val PASSWORD_MECHANISMS = "PLAIN LOGIN"

        /**
         * Picks the password mechanism for a server's capabilities (as seen after TLS),
         * or null if it offers none we can use.
         *
         * @param capabilities IMAP `CAPABILITY` atoms (`AUTH=PLAIN`, `LOGINDISABLED`) or
         *   SMTP EHLO `AUTH` mechanisms prefixed the same way.
         */
        fun selectForPassword(
            capabilities: Collection<String>,
            imap: Boolean,
        ): AuthMechanism? {
            val caps = capabilities.map { it.uppercase() }.toSet()
            val offered = caps.filter { it.startsWith("AUTH=") }.map { it.removePrefix("AUTH=") }.toSet()
            return when {
                "PLAIN" in offered -> PLAIN
                "LOGIN" in offered -> LOGIN
                imap && "LOGINDISABLED" !in caps -> IMAP_LOGIN_COMMAND
                else -> null
            }
        }
    }
}
