package tech.elephantclock.privatemail.core.mail.auth

/** A password that never prints itself. Read it with [reveal] only when handing it to the server. */
class Password(
    private val value: String,
) {
    init {
        require(value.isNotEmpty()) { "password must not be empty" }
    }

    fun reveal(): String = value

    override fun toString() = "Password(██)"

    override fun equals(other: Any?) = other is Password && other.value == value

    override fun hashCode() = value.hashCode()
}

/** Username (defaults to the full email address in the UI) and password for IMAP and SMTP. */
data class MailCredentials(
    val username: String,
    val password: Password,
) {
    init {
        require(username.isNotBlank()) { "username must not be blank" }
    }
}
