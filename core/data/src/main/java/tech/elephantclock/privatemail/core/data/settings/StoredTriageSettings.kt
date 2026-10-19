package tech.elephantclock.privatemail.core.data.settings

import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.triage.AccountTriageSettings
import tech.elephantclock.privatemail.core.data.triage.AutoFile
import tech.elephantclock.privatemail.core.data.triage.TriageSettings
import tech.elephantclock.privatemail.core.model.AccountId

/** Whether the Sent history is stored deeply enough for auto-file to be safe. */
enum class BackfillStatus {
    /** Every Sent folder's last 12 months of headers are stored, for its current UIDVALIDITY. */
    COMPLETE,

    /** Not yet (or a UIDVALIDITY reset dropped the stored history): auto-file stays off. */
    PENDING,

    /** The account has no Sent folder we know of, so nobody can be protected: auto-file stays off. */
    NO_SENT_FOLDER,
}

/**
 * The safety gate of T43: auto-file may move mail only once the Sent backfill is complete, so
 * people the user wrote to months ago are protected. Checked against the database each time:
 * a Sent folder whose UIDVALIDITY changed (the sync then deletes the old rows) closes it again.
 */
class SentBackfillGate(
    private val db: AppDatabase,
    private val store: TriageSettingsStore,
) {
    suspend fun status(id: AccountId): BackfillStatus = status(id, store.get(id))

    suspend fun status(
        id: AccountId,
        prefs: AccountPrefs,
    ): BackfillStatus {
        val paths = db.triage().sentFolders(id.value)
        if (paths.isEmpty()) return BackfillStatus.NO_SENT_FOLDER
        val complete =
            paths.all { path ->
                val entry = prefs.backfill[path]
                val folder = db.folders().get(id.value, path)
                entry != null && entry.done && folder != null && folder.uidValidity > 0 &&
                    entry.uidValidity == folder.uidValidity
            }
        return if (complete) BackfillStatus.COMPLETE else BackfillStatus.PENDING
    }
}

/**
 * [TriageSettings] from the [store], for the orchestrator. Auto-file is on only when the user
 * asked for it **and** the [gate] is open; anything else is label only, whatever was stored.
 *
 * The orchestrator's [AutoFile] has no per-folder switch, so while any value folder is switched
 * off, filing is off altogether (discard and junk still apply): mail the user didn't want filed
 * stays in the Inbox rather than going somewhere unexpected.
 */
class StoredTriageSettings(
    private val store: TriageSettingsStore,
    private val gate: SentBackfillGate,
) : TriageSettings {
    override suspend fun forAccount(id: AccountId): AccountTriageSettings {
        val prefs = store.get(id)
        val on = prefs.autoFileRequested && gate.status(id, prefs) == BackfillStatus.COMPLETE
        return AccountTriageSettings(
            autoFile =
                if (on) {
                    AutoFile(
                        enabled = true,
                        file = prefs.disabledFolders.isEmpty(),
                    )
                } else {
                    AutoFile.LABEL_ONLY
                },
            trustedAuthservIds = prefs.trustedAuthservIds,
            vips = prefs.vips,
            ownAddresses = prefs.ownAddresses,
            notifications = prefs.notifications,
        )
    }

    companion object {
        /** The same settings with nothing ever moving: for the first-run triage before Apply. */
        fun labelOnly(settings: TriageSettings) =
            TriageSettings { id -> settings.forAccount(id).copy(autoFile = AutoFile.LABEL_ONLY) }
    }
}
