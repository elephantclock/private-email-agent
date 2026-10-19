package tech.elephantclock.privatemail.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Schema migrations against the exported schemas, with data in every changed table. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun `v1 to v2 keeps every row and fills the new columns`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO accounts (id, email, emailName, displayName, username, imap_host, imap_port, " +
                    "imap_security, smtp_host, smtp_port, smtp_security, authType, providerId, createdAtMillis, " +
                    "authProblem) VALUES ('acc-1', 'me@example.com', NULL, 'Me', 'me', 'imap.example.com', 993, " +
                    "'SSL_TLS', 'smtp.example.com', 587, 'STARTTLS', 'PASSWORD', NULL, 1, NULL)",
            )
            db.execSQL(
                "INSERT INTO messages (id, accountId, folderPath, uidValidity, uid, messageIdHeader, fromEmail, " +
                    "fromName, subject, sentAtMillis, receivedAtMillis, toJson, ccJson, replyToJson, inReplyTo, " +
                    "referencesJson, flags, sizeBytes, headersJson, snippet, snippetTruncated, snippetMimeType, " +
                    "attachmentsJson, hasAttachments) VALUES (5, 'acc-1', 'INBOX', 7, 42, '<m@x>', 'a@x.example', " +
                    "NULL, 'Hello Köln', NULL, 100, '[]', '[]', '[]', NULL, '[]', 0, 10, '{}', 'Snippet', 0, " +
                    "'text/plain', '[]', 0)",
            )
            db.execSQL(
                "INSERT INTO verdicts (messageId, importance, targetRole, targetCustomName, action, source, reason, " +
                    "confidence, summary, decidedAtMillis) VALUES (5, 'LOW', 'DISCARDED', NULL, 'DISCARD', 'RULE', " +
                    "'Newsletter', 1.0, NULL, 200)",
            )
            db.execSQL(
                "INSERT INTO undo_log (id, accountId, batchId, messageId, action, fromFolder, fromUidValidity, " +
                    "fromUid, toFolder, toUid, messageIdHeader, createdAtMillis) VALUES (3, 'acc-1', 'b1', 5, " +
                    "'DISCARD', 'INBOX', 7, 42, 'Discarded', 9, '<m@x>', 300)",
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true, Migrations.MIGRATION_1_2).use { db ->
            db.query("SELECT localId, toUidValidity, toUid, fromUid, batchId FROM undo_log WHERE id = 3").use {
                assertTrue(it.moveToFirst())
                assertEquals(5L, it.getLong(0))
                assertEquals("unknown generation: undo looks the copy up by Message-ID", 0L, it.getLong(1))
                assertEquals(9L, it.getLong(2))
                assertEquals(42L, it.getLong(3))
                assertEquals("b1", it.getString(4))
            }
            db.query("SELECT pendingMove FROM verdicts WHERE messageId = 5").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT movedAtMillis, subject FROM messages WHERE id = 5").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertEquals("Hello Köln", it.getString(1))
            }
            // The FTS triggers survived (messages was not rebuilt): an update still reaches the index.
            db.execSQL("UPDATE messages SET subject = 'Grüße aus Berlin' WHERE id = 5")
            db.query("SELECT rowid FROM messages_fts WHERE messages_fts MATCH 'berlin'").use {
                assertTrue(it.moveToFirst())
                assertEquals(5L, it.getLong(0))
            }
            // A v2 entry without a local id is allowed now.
            db.execSQL(
                "INSERT INTO undo_log (accountId, batchId, localId, action, fromFolder, fromUidValidity, fromUid, " +
                    "toFolder, toUidValidity, toUid, messageIdHeader, createdAtMillis) VALUES ('acc-1', 'b2', NULL, " +
                    "'FILE', 'INBOX', 7, 43, 'Money', 11, 2, NULL, 400)",
            )
        }
    }

    @Test
    fun `v2 to v3 gives existing accounts the colour the inbox showed them`() {
        helper.createDatabase(DB, 2).use { db ->
            // Inserted out of order; the inbox numbered them by creation time.
            listOf("c" to 30, "a" to 10, "b" to 20, "f" to 60, "e" to 50, "d" to 40).forEach { (id, created) ->
                db.execSQL(
                    "INSERT INTO accounts (id, email, emailName, displayName, username, imap_host, imap_port, " +
                        "imap_security, smtp_host, smtp_port, smtp_security, authType, providerId, createdAtMillis, " +
                        "authProblem) VALUES ('$id', '$id@example.com', NULL, '$id', '$id', 'imap.example.com', 993, " +
                        "'SSL_TLS', 'smtp.example.com', 587, 'STARTTLS', 'PASSWORD', NULL, $created, NULL)",
                )
            }
        }
        helper.runMigrationsAndValidate(DB, 3, true, Migrations.MIGRATION_2_3).use { db ->
            db.query("SELECT id, colorIndex FROM accounts ORDER BY id").use {
                val colours = buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
                assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3, "e" to 4, "f" to 0), colours)
            }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
