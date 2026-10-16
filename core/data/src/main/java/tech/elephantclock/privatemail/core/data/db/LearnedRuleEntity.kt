package tech.elephantclock.privatemail.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction

enum class MatcherType { SENDER, DOMAIN }

/**
 * A `TriageRule` learned from a user correction. [accountId] null = all accounts.
 * Uniqueness uses [scope] because SQLite treats NULLs as distinct in a unique index.
 */
@Entity(
    tableName = "learned_rules",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(
            value = ["scope", "matcherType", "matcherValue"],
            unique = true,
        ), Index("accountId"), Index("matcherValue"),
    ],
)
data class LearnedRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String?,
    /** [accountId], or [ALL_ACCOUNTS]. */
    val scope: String = accountId ?: ALL_ACCOUNTS,
    val matcherType: MatcherType,
    val matcherValue: String,
    val action: VerdictAction,
    val targetRole: FolderRole,
    val targetCustomName: String?,
    val vip: Boolean,
    val createdAtMillis: Long,
) {
    init {
        // copy(accountId = …) would otherwise keep a stale scope.
        require(scope == (accountId ?: ALL_ACCOUNTS)) { "scope must match accountId" }
    }

    companion object {
        const val ALL_ACCOUNTS = "*"
    }
}
