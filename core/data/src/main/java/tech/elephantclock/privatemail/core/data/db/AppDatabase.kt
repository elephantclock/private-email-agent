package tech.elephantclock.privatemail.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        MessageFts::class,
        VerdictEntity::class,
        LearnedRuleEntity::class,
        UndoEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao

    abstract fun folders(): FolderDao

    abstract fun messages(): MessageDao

    abstract fun verdicts(): VerdictDao

    abstract fun learnedRules(): LearnedRuleDao

    abstract fun undo(): UndoDao

    abstract fun triage(): TriageDao

    abstract fun discarded(): DiscardDao

    abstract fun reading(): ReadingDao

    abstract fun firstRun(): FirstRunDao

    abstract fun rules(): RulesDao

    companion object {
        /** Excluded from backups by the app's data-extraction rules (database domain). */
        const val NAME = "mail.db"

        fun open(context: Context): AppDatabase =
            Room
                .databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
