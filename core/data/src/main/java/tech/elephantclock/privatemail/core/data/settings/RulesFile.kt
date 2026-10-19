package tech.elephantclock.privatemail.core.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.RuleMatcher

/**
 * The export/import file of screen 07: an account's folders, learned rules, VIPs, alert levels,
 * discard mode, digest and trusted authserv-ids. Deliberately **not** in it: the onboarding
 * step, the auto-file request, the Sent backfill cursors and the account's own addresses. A file
 * must never be able to open the auto-file gate or strip protection from real correspondents.
 */
@Serializable
data class RulesFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val folders: List<FileFolder> = emptyList(),
    val customFolders: List<FileCustomFolder> = emptyList(),
    val rules: List<FileRule> = emptyList(),
    val vips: List<String> = emptyList(),
    val alertUrgent: Boolean = true,
    val alertHigh: Boolean = true,
    val discardMode: DiscardMode = DiscardMode.HOLD_30_DAYS,
    val digest: Boolean = true,
    val trustedAuthservIds: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = "private-email-agent/rules"
        const val VERSION = 1
    }
}

/** A built-in folder: Priority or a value folder. [description] null = the default one. */
@Serializable
data class FileFolder(
    val role: FolderRole,
    val enabled: Boolean = true,
    val description: String? = null,
)

@Serializable
data class FileCustomFolder(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
)

@Serializable
data class FileRule(
    /** "sender" or "domain". */
    val matcher: String,
    val value: String,
    val target: FolderRole,
    val customName: String? = null,
    val vip: Boolean = false,
)

/** Writes [RulesFile]s (the reading side, with all its checks, is [RulesImport]). */
object RulesExport {
    /** Strict on reading: unknown keys, wrong types and unknown enum values all fail. */
    internal val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    suspend fun build(
        db: AppDatabase,
        store: TriageSettingsStore,
        account: AccountId,
    ): RulesFile {
        val prefs = store.get(account)
        val digest = store.digest()
        val rules =
            db
                .learnedRules()
                .forAccount(account.value)
                .mapNotNull { runCatching { it.toModel() }.getOrNull() }
                // A rule into a custom folder this account doesn't have couldn't be imported: left out.
                .filter { r ->
                    r.target.role != FolderRole.CUSTOM ||
                        prefs.customFolders.any { it.name == r.target.customName }
                }
        return RulesFile(
            folders =
                DESCRIBED_FOLDERS.map { role ->
                    FileFolder(
                        role,
                        role == FolderRole.PRIORITY || prefs.folderEnabled(role),
                        prefs.folderDescriptions[role],
                    )
                },
            customFolders = prefs.customFolders.map { FileCustomFolder(it.name, it.description, it.enabled) },
            rules =
                rules.map { r ->
                    when (val m = r.matcher) {
                        is RuleMatcher.Sender -> FileRule("sender", m.email, r.target.role, r.target.customName, r.vip)
                        is RuleMatcher.Domain -> FileRule("domain", m.domain, r.target.role, r.target.customName, r.vip)
                    }
                },
            vips = prefs.vips.sorted(),
            alertUrgent = prefs.alertUrgent,
            alertHigh = prefs.alertHigh,
            discardMode = prefs.discardMode,
            digest = digest,
            trustedAuthservIds = prefs.trustedAuthservIds.sorted(),
        )
    }

    fun encode(file: RulesFile): ByteArray = json.encodeToString(RulesFile.serializer(), file).encodeToByteArray()
}
