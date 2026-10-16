package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

/** An email address; [email] is stored lower-cased so it can be compared directly. */
@Serializable
data class Address(
    val email: String,
    val name: String? = null,
) {
    init {
        require(email == email.trim().lowercase()) { "email must be trimmed and lower-case; use Address.of()" }
        require('@' in email) { "not an email address: $email" }
    }

    val localPart: String get() = email.substringBeforeLast('@')
    val domain: String get() = email.substringAfterLast('@')

    companion object {
        fun of(
            email: String,
            name: String? = null,
        ) = Address(email.trim().lowercase(), name?.trim()?.takeIf { it.isNotEmpty() })
    }
}

/** Identifies one message on the server. A UIDVALIDITY change makes old refs stale. */
@Serializable
data class MessageRef(
    val accountId: AccountId,
    val folderPath: String,
    val uidValidity: Long,
    val uid: Long,
) {
    init {
        require(uid > 0) { "uid must be positive" }
        require(uidValidity > 0) { "uidValidity must be positive" }
    }
}

@Serializable
enum class MessageFlag { SEEN, ANSWERED, FLAGGED, DRAFT, DELETED }

/** Envelope, flags and the raw headers triage reads. */
@Serializable
data class MessageHeader(
    val ref: MessageRef,
    val messageId: String?,
    val from: Address?,
    val subject: String,
    val sentAtMillis: Long?,
    val receivedAtMillis: Long,
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val replyTo: List<Address> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val flags: Set<MessageFlag> = emptySet(),
    val sizeBytes: Long = 0,
    /** Selected raw headers, names lower-cased (e.g. "list-unsubscribe"). */
    val headers: Map<String, List<String>> = emptyMap(),
) {
    init {
        require(headers.keys.all { it == it.lowercase() }) { "header names must be lower-case" }
    }

    /** First value of a header, case-insensitive. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val isReply: Boolean get() = inReplyTo != null || references.isNotEmpty()
}
