package tech.elephantclock.privatemail.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema migrations. Each is tested against the exported schema in `core/data/schemas/`. */
object Migrations {
    /**
     * v1 → v2 (T24):
     * - `undo_log`: `messageId` (NOT NULL) becomes the nullable `localId`, mirroring
     *   `UndoEntry.localId`, and `toUidValidity` is added. SQLite can't change nullability, so the
     *   table is rebuilt. Old entries get `toUidValidity = 0` ("unknown"): undo then finds the
     *   copy by Message-ID instead of trusting a `toUid` from an unknown folder generation.
     * - `messages.movedAtMillis`: when the app moved the message (the discard time for the purge).
     *   Added with ALTER TABLE: rebuilding `messages` would drop its FTS triggers.
     * - `verdicts.pendingMove`: a triage move not yet confirmed by the server.
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `movedAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `verdicts` ADD COLUMN `pendingMove` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `undo_log_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` TEXT NOT NULL, `batchId` TEXT NOT NULL, `localId` INTEGER, " +
                        "`action` TEXT NOT NULL, `fromFolder` TEXT NOT NULL, `fromUidValidity` INTEGER NOT NULL, " +
                        "`fromUid` INTEGER NOT NULL, `toFolder` TEXT NOT NULL, `toUidValidity` INTEGER NOT NULL, " +
                        "`toUid` INTEGER, `messageIdHeader` TEXT, `createdAtMillis` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "INSERT INTO `undo_log_new` (`id`, `accountId`, `batchId`, `localId`, `action`, `fromFolder`, " +
                        "`fromUidValidity`, `fromUid`, `toFolder`, `toUidValidity`, `toUid`, `messageIdHeader`, " +
                        "`createdAtMillis`) " +
                        "SELECT `id`, `accountId`, `batchId`, `messageId`, `action`, `fromFolder`, " +
                        "`fromUidValidity`, `fromUid`, `toFolder`, 0, `toUid`, `messageIdHeader`, `createdAtMillis` " +
                        "FROM `undo_log`",
                )
                db.execSQL("DROP TABLE `undo_log`")
                db.execSQL("ALTER TABLE `undo_log_new` RENAME TO `undo_log`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_undo_log_createdAtMillis` ON `undo_log` (`createdAtMillis`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_undo_log_accountId_batchId` ON `undo_log` (`accountId`, `batchId`)",
                )
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
