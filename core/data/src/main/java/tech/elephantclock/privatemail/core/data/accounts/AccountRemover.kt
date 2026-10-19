package tech.elephantclock.privatemail.core.data.accounts

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.ServerColumns
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.model.AccountId
import java.util.Locale

/** A server endpoint, normalised like certificate pins are keyed (lower case, no trailing dot). */
data class HostPort(
    val host: String,
    val port: Int,
) {
    companion object {
        fun of(
            host: String,
            port: Int,
        ) = HostPort(host.lowercase(Locale.ROOT).trimEnd('.'), port)
    }
}

/** What removing an account must clear outside the database and secret store; the app implements it. */
interface AccountRemovalHooks {
    /** Cancels the account's background work (first run, archive taps) and waits for that to be recorded. */
    suspend fun stopWork(id: AccountId)

    /** Removes the account's notifications. */
    fun clearNotifications(id: AccountId)

    /** Forgets certificates the user trusted for these servers (none of them is used by another account). */
    fun forgetPins(servers: Set<HostPort>)

    /** Deletes files kept for these messages (downloaded attachments). */
    fun deleteFiles(messageIds: List<Long>)
}

/**
 * Removes an account from this device only (T52); the server is never contacted (this class
 * has no connector). In this order, so a failure before the database row goes leaves the account
 * listed and removal can simply be tried again:
 * 1. background work and notifications;
 * 2. the password blob and its Keystore key;
 * 3. trusted certificates of its IMAP and SMTP servers, unless another account uses the same host:port;
 * 4. the database row, which cascades to folders, messages (and their search index), verdicts,
 *    learned rules and undo entries;
 * 5. its settings (after the row: a listed account without settings would be sent back into onboarding);
 * 6. downloaded attachments.
 */
class AccountRemover(
    private val db: AppDatabase,
    private val secrets: SecretStore,
    private val settings: TriageSettingsStore,
    private val hooks: AccountRemovalHooks,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** False when there is no such account. Main-safe: everything runs on [io] (prefs commits, Keystore, files). */
    suspend fun remove(id: AccountId): Boolean = withContext(io) { removeOnIo(id) }

    private suspend fun removeOnIo(id: AccountId): Boolean {
        val account = db.accounts().get(id.value) ?: return false
        hooks.stopWork(id)
        hooks.clearNotifications(id)
        val messageIds = db.accounts().messageIds(id.value)
        secrets.delete(id)
        val stillUsed =
            db
                .accounts()
                .all()
                .first()
                .filter { it.id != id.value }
                .flatMap { listOf(it.imap.hostPort(), it.smtp.hostPort()) }
                .toSet()
        hooks.forgetPins(setOf(account.imap.hostPort(), account.smtp.hostPort()) - stillUsed)
        db.accounts().delete(id.value)
        settings.remove(id)
        hooks.deleteFiles(messageIds)
        return true
    }

    private fun ServerColumns.hostPort() = HostPort.of(host, port)
}
