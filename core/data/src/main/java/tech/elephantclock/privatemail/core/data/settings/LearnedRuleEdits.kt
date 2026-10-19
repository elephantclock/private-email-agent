package tech.elephantclock.privatemail.core.data.settings

import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules

/** How a learned-rule edit ended. */
enum class RuleEditResult { DONE, GONE, VIP_RULE, INVALID }

/**
 * The rules screen's edits of learned rules (T48). Every change is rebuilt through the
 * [TriageRule] constructor, so its invariants hold (a VIP rule KEEPs; an action fits its
 * target). A VIP rule's target is never changed: it is always Priority, and "VIP but filed
 * elsewhere" isn't a thing; the user deletes it instead. Protections are not touched here:
 * the rules engine skips any rule a protection forbids, whatever its target.
 */
class LearnedRuleEdits(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun delete(id: Long) = db.learnedRules().delete(id)

    suspend fun retarget(
        id: Long,
        target: FolderTarget,
    ): RuleEditResult {
        val rule = db.rules().rule(id)?.let { runCatching { it.toModel() }.getOrNull() } ?: return RuleEditResult.GONE
        if (rule.vip) return RuleEditResult.VIP_RULE
        val updated =
            try {
                TriageRule(
                    id = rule.id,
                    accountId = rule.accountId,
                    matcher = rule.matcher,
                    action = LearnedRules.actionFor(target),
                    target = target,
                    // The newest correction wins among overlapping rules.
                    createdAtMillis = clock(),
                    vip = false,
                )
            } catch (_: IllegalArgumentException) {
                return RuleEditResult.INVALID
            }
        db.learnedRules().put(updated.toEntity())
        return RuleEditResult.DONE
    }

    companion object {
        /** Targets a rule can be changed to (custom folders are added by the caller). */
        val TARGET_ROLES: List<FolderRole> =
            listOf(FolderRole.PRIORITY) + VALUE_FOLDERS + listOf(FolderRole.DISCARDED, FolderRole.JUNK)
    }
}
