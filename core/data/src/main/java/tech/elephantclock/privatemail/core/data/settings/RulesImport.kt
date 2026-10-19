package tech.elephantclock.privatemail.core.data.settings

import androidx.room.withTransaction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.onboarding.SentPeople
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.triage.rules.Correction
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules
import java.util.Locale

/** Why a file was refused. The whole file is refused on the first problem: nothing half-imported. */
enum class ImportProblem {
    TOO_LARGE,
    NOT_JSON,
    WRONG_FORMAT,
    NEWER_VERSION,
    TOO_MANY,
    BAD_FOLDER,
    BAD_RULE,
    BAD_ADDRESS,
    BAD_AUTHSERV_ID,
}

sealed interface ImportResult {
    data class Ok(
        val plan: ImportPlan,
    ) : ImportResult

    data class Invalid(
        val problem: ImportProblem,
    ) : ImportResult
}

/**
 * A checked file, ready to apply to the account the user chose (account ids in a file are never
 * read). [authservIds] are applied only when the user confirms them: trusting a forged
 * authserv-id would make spoofed `Authentication-Results` count, so they are shown first.
 */
data class ImportPlan(
    val file: RulesFile,
    val rules: List<TriageRule>,
    val authservIds: Set<String>,
) {
    /**
     * Replaces the account's folders, alert levels, discard mode, VIPs and digest; adds the
     * rules (a rule with the same matcher is replaced).
     */
    suspend fun apply(
        db: AppDatabase,
        store: TriageSettingsStore,
        account: AccountId,
        trustAuthservIds: Boolean,
    ) {
        val f = file
        store.update(account) { p ->
            p.copy(
                disabledFolders =
                    f.folders
                        .filterNot { it.enabled || it.role == FolderRole.PRIORITY }
                        .map { it.role }
                        .toSet(),
                folderDescriptions =
                    f.folders
                        .mapNotNull { e ->
                            e.description?.trim()?.let { e.role to it }
                        }.toMap(),
                customFolders =
                    f.customFolders.map {
                        CustomFolder(
                            it.name.trim(),
                            it.description.trim(),
                            it.enabled,
                        )
                    },
                vips = f.vips.map(SentPeople::key).toSet(),
                alertUrgent = f.alertUrgent,
                alertHigh = f.alertHigh,
                discardMode = f.discardMode,
                trustedAuthservIds = if (trustAuthservIds) authservIds else p.trustedAuthservIds,
            )
        }
        store.setDigest(f.digest)
        db.withTransaction { rules.forEach { db.learnedRules().put(it.toEntity()) } }
    }
}

/**
 * Reads a rules file strictly (T48): a size cap before parsing, a JSON decoder that refuses
 * unknown keys, wrong types and unknown values, caps on every list and string, and each rule
 * rebuilt through [TriageRule] (VIP rules KEEP in Priority; free-mail domains are refused, as
 * "discard everyone at gmail.com" is never meant). Nothing in the file is trusted blindly.
 */
object RulesImport {
    const val MAX_BYTES = 256 * 1024
    const val MAX_RULES = 1_000
    const val MAX_VIPS = 1_000
    const val MAX_AUTHSERV_IDS = 10
    private const val MAX_ADDRESS = 254
    private const val MAX_AUTHSERV = 100

    private val json = Json { ignoreUnknownKeys = false }

    fun parse(
        bytes: ByteArray,
        account: AccountId,
        now: Long = System.currentTimeMillis(),
    ): ImportResult {
        if (bytes.size > MAX_BYTES) return ImportResult.Invalid(ImportProblem.TOO_LARGE)
        val file =
            try {
                json.decodeFromString(RulesFile.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
            } catch (_: SerializationException) {
                return ImportResult.Invalid(ImportProblem.NOT_JSON)
            } catch (_: IllegalArgumentException) {
                return ImportResult.Invalid(ImportProblem.NOT_JSON)
            } catch (_: CharacterCodingException) {
                return ImportResult.Invalid(ImportProblem.NOT_JSON)
            } catch (_: StackOverflowError) {
                return ImportResult.Invalid(ImportProblem.NOT_JSON)
            }
        if (file.format != RulesFile.FORMAT) return ImportResult.Invalid(ImportProblem.WRONG_FORMAT)
        if (file.version > RulesFile.VERSION ||
            file.version < 1
        ) {
            return ImportResult.Invalid(ImportProblem.NEWER_VERSION)
        }
        if (file.rules.size > MAX_RULES || file.vips.size > MAX_VIPS ||
            file.customFolders.size > FolderNames.MAX_CUSTOM_FOLDERS ||
            file.trustedAuthservIds.size > MAX_AUTHSERV_IDS || file.folders.size > DESCRIBED_FOLDERS.size
        ) {
            return ImportResult.Invalid(ImportProblem.TOO_MANY)
        }
        if (!foldersOk(file)) return ImportResult.Invalid(ImportProblem.BAD_FOLDER)
        if (!file.vips.all(::isAddress)) return ImportResult.Invalid(ImportProblem.BAD_ADDRESS)
        if (!file.trustedAuthservIds.all(::isAuthservId)) return ImportResult.Invalid(ImportProblem.BAD_AUTHSERV_ID)
        val customNames = file.customFolders.map { it.name.trim() }
        val rules =
            file.rules.map { r ->
                rule(r, account, customNames, now)
                    ?: return ImportResult.Invalid(ImportProblem.BAD_RULE)
            }
        return ImportResult.Ok(
            ImportPlan(file, rules, file.trustedAuthservIds.map { it.lowercase(Locale.ROOT) }.toSet()),
        )
    }

    private fun foldersOk(file: RulesFile): Boolean {
        val roles = file.folders.map { it.role }
        if (roles.toSet().size != roles.size || roles.any { it !in DESCRIBED_FOLDERS }) return false
        if (file.folders.any { f ->
                f.description?.let { FolderNames.checkDescription(it) != null } == true
            }
        ) {
            return false
        }
        val seen = mutableListOf<String>()
        for (c in file.customFolders) {
            if (FolderNames.checkName(c.name, seen) != null ||
                FolderNames.checkDescription(c.description) != null
            ) {
                return false
            }
            seen += c.name.trim()
        }
        return true
    }

    private fun rule(
        r: FileRule,
        account: AccountId,
        customNames: List<String>,
        now: Long,
    ): TriageRule? {
        if (r.target == FolderRole.CUSTOM) {
            val name = r.customName ?: return null
            if (customNames.none { it == name }) return null
        } else if (r.customName != null) {
            return null
        }
        if (r.vip && r.target != FolderRole.PRIORITY) return null
        val matcher =
            when (r.matcher) {
                "sender" -> {
                    r.value
                        .takeIf {
                            isAddress(
                                it,
                            ) && it == it.lowercase(Locale.ROOT)
                        }?.let(RuleMatcher::Sender)
                }

                "domain" -> {
                    if (isRuleDomain(r.value)) RuleMatcher.Domain(r.value) else null
                }

                else -> {
                    null
                }
            } ?: return null
        val target = runCatching { FolderTarget(r.target, r.customName) }.getOrNull() ?: return null
        return runCatching {
            TriageRule(0, account, matcher, LearnedRules.actionFor(target), target, now, r.vip)
        }.getOrNull()
    }

    internal fun isAddress(s: String): Boolean {
        if (s.length > MAX_ADDRESS || s.any { it.isWhitespace() || it.isISOControl() }) return false
        val at = s.lastIndexOf('@')
        if (at <= 0 || at > 64 || s.indexOf('@') != at) return false
        return isDomain(s.substring(at + 1).lowercase(Locale.ROOT))
    }

    private fun isDomain(d: String): Boolean =
        d.length in 3..253 && '.' in d && !d.startsWith('.') && !d.endsWith('.') && ".." !in d &&
            d.all {
                it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' ||
                    (it.code > 127 && it.isLetterOrDigit())
            }

    private fun isRuleDomain(d: String): Boolean = d == d.lowercase(Locale.ROOT) && isDomain(d) && freeMailSafe(d)

    /** The check corrections get: on a free-mail domain a domain rule becomes a sender rule; refused here. */
    private fun freeMailSafe(d: String): Boolean {
        val probe = Correction(null, Address.of("x@$d"), FolderTarget.Discarded, Correction.Scope.DOMAIN)
        return LearnedRules.fromCorrection(probe, 0, 0).matcher is RuleMatcher.Domain
    }

    private fun isAuthservId(s: String): Boolean =
        s.length in 1..MAX_AUTHSERV &&
            s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }
}
