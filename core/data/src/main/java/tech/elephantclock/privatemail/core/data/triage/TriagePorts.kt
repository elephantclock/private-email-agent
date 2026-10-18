package tech.elephantclock.privatemail.core.data.triage

import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.rules.Decision
import java.util.concurrent.ConcurrentHashMap

/**
 * The on-device model, as triage sees it: classifies a message the rules couldn't decide.
 * `core:ai`'s engine is adapted to this in the app. Returns null when the model is unavailable
 * (no Gemini Nano, still downloading, busy, failed): the message then stays in Priority,
 * rules-only. Whatever it returns goes through [Decision.NeedsAi.accept] and the protections.
 */
fun interface TriageAi {
    suspend fun classify(
        message: MailItem,
        decision: Decision.NeedsAi,
    ): Verdict?

    companion object {
        val UNAVAILABLE = TriageAi { _, _ -> null }
    }
}

/**
 * The user's auto-file switches. Until the user has applied the first run ([enabled] false,
 * the default) triage only labels: verdicts are stored, nothing moves on the server.
 */
data class AutoFile(
    val enabled: Boolean = false,
    val file: Boolean = true,
    val discard: Boolean = true,
    val junk: Boolean = true,
) {
    /** Whether triage may move a message for [action] (KEEP never moves). */
    fun allows(action: VerdictAction): Boolean =
        enabled &&
            when (action) {
                VerdictAction.KEEP -> false
                VerdictAction.FILE -> file
                VerdictAction.DISCARD -> discard
                VerdictAction.JUNK -> junk
            }

    companion object {
        val LABEL_ONLY = AutoFile()
        val ALL = AutoFile(enabled = true)
    }
}

/** Per-account triage settings (T42/T48 edit them; the app backs them with DataStore). */
data class AccountTriageSettings(
    val autoFile: AutoFile = AutoFile.LABEL_ONLY,
    /** The user's server's authserv-ids; only their `Authentication-Results` are trusted. */
    val trustedAuthservIds: Set<String> = emptySet(),
    /** VIP addresses (also learned VIP rules); always Priority, URGENT. */
    val vips: Set<String> = emptySet(),
    /** Aliases of the account's own address: never "sent to", and forged "from me" mail isn't protected. */
    val ownAddresses: Set<String> = emptySet(),
    val notifications: Boolean = true,
)

fun interface TriageSettings {
    suspend fun forAccount(id: AccountId): AccountTriageSettings

    companion object {
        /** Label only, everything else default. */
        val DEFAULTS = TriageSettings { AccountTriageSettings() }
    }
}

/** Settings held in memory; for tests, and for the app until its DataStore-backed version exists. */
class InMemoryTriageSettings(
    private val default: AccountTriageSettings = AccountTriageSettings(),
) : TriageSettings {
    private val byAccount = ConcurrentHashMap<AccountId, AccountTriageSettings>()

    fun set(
        id: AccountId,
        settings: AccountTriageSettings,
    ) {
        byAccount[id] = settings
    }

    override suspend fun forAccount(id: AccountId): AccountTriageSettings = byAccount[id] ?: default
}

/** A message worth a notification: URGENT or HIGH, new, unread and still in the Inbox. */
data class TriageAlert(
    val accountId: AccountId,
    val localId: Long,
    val from: Address?,
    val subject: String,
    val importance: Importance,
    val reason: String,
)

/** Posts notifications (the app, T49). Called after the verdict is stored. */
fun interface TriageNotifier {
    fun notify(alert: TriageAlert)

    companion object {
        val NONE = TriageNotifier { }
    }
}
