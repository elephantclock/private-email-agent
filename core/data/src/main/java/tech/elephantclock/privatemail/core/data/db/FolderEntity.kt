package tech.elephantclock.privatemail.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.SpecialUse

/** A server folder and where its last sync stopped (`SyncState`). */
@Entity(
    tableName = "folders",
    primaryKeys = ["accountId", "path"],
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "role")],
)
data class FolderEntity(
    val accountId: String,
    val path: String,
    val displayName: String,
    val uidValidity: Long,
    val specialUse: SpecialUse?,
    val role: FolderRole?,
    val description: String?,
    /** Sync state: 0 / null until the first sync. */
    val highestUid: Long = 0,
    val lastSyncMillis: Long? = null,
    /** HIGHESTMODSEQ at the last flag check (CONDSTORE servers); null = unknown, fetch every flag. */
    val highestModSeq: Long? = null,
)
