package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a learned rule matches on. */
@Serializable
sealed interface RuleMatcher {
    fun matches(sender: Address): Boolean

    @Serializable
    @SerialName("sender")
    data class Sender(
        val email: String,
    ) : RuleMatcher {
        init {
            require(email == email.lowercase() && '@' in email) { "sender must be a lower-case address" }
        }

        override fun matches(sender: Address) = sender.email == email
    }

    /** Matches the domain and its subdomains (`example.com` matches `mail.example.com`). */
    @Serializable
    @SerialName("domain")
    data class Domain(
        val domain: String,
    ) : RuleMatcher {
        init {
            require(domain == domain.lowercase() && '@' !in domain && domain.isNotBlank()) {
                "domain must be lower-case without '@'"
            }
        }

        override fun matches(sender: Address) = sender.domain == domain || sender.domain.endsWith(".$domain")
    }
}

/** A rule learned from a user correction: sender or domain → folder or action. */
@Serializable
data class TriageRule(
    val id: Long,
    val accountId: AccountId?,
    val matcher: RuleMatcher,
    val action: VerdictAction,
    val target: FolderTarget,
    val createdAtMillis: Long,
    /** Marks the sender as VIP: always Priority and URGENT alerts. */
    val vip: Boolean = false,
) {
    init {
        require(!vip || action == VerdictAction.KEEP) { "a VIP rule must KEEP" }
        require(action.allows(target.role)) { "$action cannot target ${target.role}" }
    }

    fun appliesTo(
        account: AccountId,
        sender: Address,
    ): Boolean = (accountId == null || accountId == account) && matcher.matches(sender)
}
