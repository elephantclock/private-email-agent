package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AuthResult { PASS, FAIL, SOFTFAIL, NEUTRAL, NONE, TEMPERROR, PERMERROR, UNKNOWN }

/** SPF/DKIM/DMARC from the receiving server's `Authentication-Results`. */
@Serializable
data class AuthResults(
    val spf: AuthResult = AuthResult.UNKNOWN,
    val dkim: AuthResult = AuthResult.UNKNOWN,
    val dmarc: AuthResult = AuthResult.UNKNOWN,
) {
    val anyFail: Boolean
        get() = listOf(spf, dkim, dmarc).any { it == AuthResult.FAIL || it == AuthResult.PERMERROR }
}

/** Facts extracted from a message's headers and preview (T20). No decisions here. */
@Serializable
data class Signals(
    val hasListUnsubscribe: Boolean = false,
    val listId: String? = null,
    val precedenceBulk: Boolean = false,
    /** Email service provider recognised from its headers, e.g. "mailgun". */
    val esp: String? = null,
    val noReplySender: Boolean = false,
    val autoSubmitted: Boolean = false,
    val auth: AuthResults = AuthResults(),
    val hiddenCharacters: Boolean = false,
    /** Brand or frequent domain the sender's domain imitates. */
    val lookalikeOf: String? = null,
    val securityCode: Boolean = false,
    val moneySignal: Boolean = false,
    val isReply: Boolean = false,
) {
    val isBulk: Boolean get() = hasListUnsubscribe || listId != null || precedenceBulk || esp != null
}
