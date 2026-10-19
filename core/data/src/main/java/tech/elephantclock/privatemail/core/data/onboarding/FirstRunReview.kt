package tech.elephantclock.privatemail.core.data.onboarding

import tech.elephantclock.privatemail.core.data.db.ReviewRow
import tech.elephantclock.privatemail.core.data.db.VerdictCount
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.VerdictAction

/** One group of screen 03: where a set of Inbox messages would go on Apply. */
data class ReviewGroup(
    val action: VerdictAction,
    val target: FolderTarget,
    val count: Int,
) {
    val keeps: Boolean get() = action == VerdictAction.KEEP || action == VerdictAction.FILE
}

/** The keep/discard summary of the first run, from the stored verdicts. */
data class FirstRunReview(
    val groups: List<ReviewGroup>,
    /** Inbox messages still waiting for a verdict (0 once the sort is finished). */
    val untriaged: Int = 0,
) {
    val keep: List<ReviewGroup> get() = groups.filter { it.keeps }
    val discard: List<ReviewGroup> get() = groups.filter { !it.keeps }
    val keepCount: Int get() = keep.sumOf { it.count }
    val discardCount: Int get() = discard.sumOf { it.count }
    val total: Int get() = keepCount + discardCount

    /** What Apply would move into folders (Priority stays where it is). */
    val fileCount: Int get() = keep.filter { it.action == VerdictAction.FILE }.sumOf { it.count }

    companion object {
        private val ORDER =
            listOf(
                FolderRole.PRIORITY,
                FolderRole.PEOPLE,
                FolderRole.MONEY,
                FolderRole.WORK,
                FolderRole.ORDERS,
                FolderRole.REPORTS,
                FolderRole.CUSTOM,
                FolderRole.DISCARDED,
                FolderRole.JUNK,
            )

        /**
         * Groups the counts as screen 03 shows them. Mail for a value folder the user switched
         * off stays in Priority, since Apply won't file it.
         */
        fun of(
            counts: List<VerdictCount>,
            prefs: AccountPrefs,
            untriaged: Int = 0,
        ): FirstRunReview {
            val groups =
                counts
                    .map { c ->
                        val target =
                            if (c.targetRole == FolderRole.CUSTOM) {
                                FolderTarget.custom(c.targetCustomName ?: "?")
                            } else {
                                FolderTarget(c.targetRole)
                            }
                        if (c.action == VerdictAction.FILE && !prefs.folderEnabled(c.targetRole)) {
                            ReviewGroup(VerdictAction.KEEP, FolderTarget.Priority, c.count)
                        } else {
                            ReviewGroup(c.action, target, c.count)
                        }
                    }.groupBy { it.action to it.target }
                    .map { (key, list) -> ReviewGroup(key.first, key.second, list.sumOf { it.count }) }
                    .sortedWith(compareBy({ ORDER.indexOf(it.target.role) }, { it.target.customName }))
            return FirstRunReview(groups, untriaged)
        }
    }
}

/** A drill-down line: who, what and why. */
data class ReviewItem(
    val id: Long,
    val from: String,
    val subject: String,
    val receivedAtMillis: Long,
    val reason: String,
) {
    companion object {
        fun of(row: ReviewRow) =
            ReviewItem(
                id = row.id,
                from = row.fromName?.takeIf { it.isNotBlank() } ?: row.fromEmail.orEmpty(),
                subject = row.subject,
                receivedAtMillis = row.receivedAtMillis,
                reason = row.reason,
            )
    }
}
