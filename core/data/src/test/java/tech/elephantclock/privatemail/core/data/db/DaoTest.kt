package tech.elephantclock.privatemail.core.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.model.AuthType
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val messages = db.messages()
    private val verdicts = db.verdicts()

    @After
    fun tearDown() = db.close()

    private suspend fun withAccount() = db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1))

    private suspend fun insert(vararg e: MessageEntity) = messages.insertAll(e.toList())

    @Test
    fun `account and message round trip through the model types`() =
        runTest {
            withAccount()
            assertEquals(DbFixtures.account, db.accounts().get("acc-1")!!.toModel())
            assertEquals(
                AuthType.PASSWORD,
                db
                    .accounts()
                    .all()
                    .first()
                    .single()
                    .authType,
            )

            val h = DbFixtures.header(42)
            val b = DbFixtures.body(h)
            val id = insert(messageEntity(h, b)).single()
            val stored = messages.get(id)!!
            assertEquals(h, stored.toHeader())
            assertEquals(b, stored.toBody())
            assertTrue(stored.hasAttachments)
        }

    @Test
    fun `the same server message is stored once, flags update in place`() =
        runTest {
            withAccount()
            val first = insert(DbFixtures.entity(1)).single()
            assertEquals(listOf(-1L), insert(DbFixtures.entity(1)))
            messages.updateFlags("acc-1", "INBOX", 7, 1, flags = 0)
            assertEquals(0, messages.byServerRef("acc-1", "INBOX", 7, 1)!!.flags)
            assertEquals(first, messages.byServerRef("acc-1", "INBOX", 7, 1)!!.id)
        }

    @Test
    fun `a move keeps the local id and its verdict`() =
        runTest {
            withAccount()
            val id = insert(DbFixtures.entity(5)).single()
            verdicts.upsert(DbFixtures.keep.toEntity(id, 1))
            messages.moveTo(id, "Money", 99, 1)
            assertNull(messages.byServerRef("acc-1", "INBOX", 7, 5))
            assertEquals(id, messages.byServerRef("acc-1", "Money", 99, 1)!!.id)
            assertEquals(DbFixtures.keep, verdicts.get(id)!!.toModel())
        }

    @Test
    fun `verdict upsert replaces, and untriaged lists only messages without one`() =
        runTest {
            withAccount()
            val (a, b) = insert(DbFixtures.entity(1), DbFixtures.entity(2))
            verdicts.upsert(DbFixtures.keep.toEntity(a, 1))
            assertEquals(listOf(b), messages.untriaged(10).map { it.id })

            val corrected =
                Verdict.file(
                    FolderTarget.custom("Partners"),
                    Importance.NORMAL,
                    VerdictSource.USER,
                    "Moved by you",
                )
            verdicts.upsert(corrected.toEntity(a, 2))
            assertEquals(corrected, verdicts.get(a)!!.toModel())
        }

    @Test
    fun `priority pages show kept messages only, newest first`() =
        runTest {
            withAccount()
            val ids = messages.insertAll((1L..120L).map { DbFixtures.entity(it) })
            verdicts.upsertAll(
                ids.mapIndexed { i, id -> (if (i % 4 == 0) DbFixtures.discard else DbFixtures.keep).toEntity(id, 1) },
            )
            val source = messages.priority()
            val first = source.load(PagingSource.LoadParams.Refresh(null, 30, false)) as PagingSource.LoadResult.Page
            assertEquals(30, first.data.size)
            assertEquals(
                120L,
                first.data
                    .first()
                    .message.uid,
            )
            assertTrue(first.data.all { it.verdict?.action == VerdictAction.KEEP })
            val second =
                source.load(
                    PagingSource.LoadParams.Append(first.nextKey!!, 30, false),
                ) as PagingSource.LoadResult.Page
            assertEquals(30, second.data.size)
            val all = (first.data + second.data).map { it.message.uid }
            assertEquals(all.sortedDescending(), all)
            assertEquals(all.size, all.toSet().size)
        }

    @Test
    fun `folder pages include untriaged messages`() =
        runTest {
            withAccount()
            insert(DbFixtures.entity(1), DbFixtures.entity(2))
            val page = messages.inFolder("acc-1", "INBOX").load(PagingSource.LoadParams.Refresh(null, 10, false))
            assertEquals(listOf(2L, 1L), (page as PagingSource.LoadResult.Page).data.map { it.message.uid })
            assertNull(page.data.first().verdict)
        }

    @Test
    fun `full-text search over subject, sender and snippet, with prefixes and any language`() =
        runTest {
            withAccount()
            val invoice = insert(DbFixtures.entity(1, subject = "Your invoice for October")).single()
            val russian = insert(DbFixtures.entity(2, subject = "Заказ", text = "Ваш тур оплачен")).single()
            val sender =
                insert(
                    DbFixtures.entity(3, subject = "Hello", from = "anna@partner.example", fromName = "Anna Schmidt"),
                ).single()

            fun ids(q: String) =
                FtsQuery
                    .of(q)
                    ?.let {
                        kotlinx.coroutines.runBlocking { messages.search(it) }
                    }?.map { it.message.id }
            assertEquals(listOf(invoice), ids("invoi"))
            assertEquals(listOf(invoice), ids("INVOICE october"))
            assertEquals(listOf(russian), ids("опла"))
            // Capitalised non-ASCII words must match in any case (FTS4's "simple" tokenizer folds ASCII only).
            assertEquals(listOf(russian), ids("заказ"))
            assertEquals(listOf(russian), ids("Заказ"))
            val anna =
                insert(
                    DbFixtures.entity(4, subject = "Привет", from = "anna@ru.example", fromName = "Анна Петрова"),
                ).single()
            assertEquals(listOf(anna), ids("анна"))
            val koeln = insert(DbFixtures.entity(5, subject = "Grüße aus Köln")).single()
            assertEquals(listOf(koeln), ids("koln"))
            assertEquals(listOf(sender), ids("schmidt"))
            assertEquals(listOf(sender), ids("anna@partner"))
            assertEquals(emptyList<Long>(), ids("invoice hello"))
            // Operators and quotes the user types are just text.
            assertEquals(emptyList<Long>(), ids("\"x\" OR NEAR( * -"))
            assertNull(FtsQuery.of("  ** "))

            messages.deleteFolder("acc-1", "INBOX")
            assertEquals(emptyList<Long>(), ids("invoice"))
        }

    @Test
    fun `removing an account cascades to everything it owns`() =
        runTest {
            withAccount()
            db.folders().upsert(
                listOf(FolderEntity("acc-1", "INBOX", "Inbox", 7, SpecialUse.INBOX, FolderRole.PRIORITY, null)),
            )
            val id = insert(DbFixtures.entity(1)).single()
            verdicts.upsert(DbFixtures.keep.toEntity(id, 1))
            db.accounts().delete("acc-1")
            assertNull(messages.get(id))
            assertNull(verdicts.get(id))
            assertEquals(emptyList<FolderEntity>(), db.folders().forAccount("acc-1"))
        }

    @Test
    fun `folder sync state`() =
        runTest {
            withAccount()
            db.folders().upsert(
                listOf(FolderEntity("acc-1", "INBOX", "Inbox", 7, SpecialUse.INBOX, FolderRole.PRIORITY, null)),
            )
            db.folders().updateSyncState("acc-1", "INBOX", 8, 500, 123)
            val f = db.folders().get("acc-1", "INBOX")!!
            assertEquals(Triple(8L, 500L, 123L), Triple(f.uidValidity, f.highestUid, f.lastSyncMillis))
        }

    @Test
    fun `learned rules - newest correction for a matcher wins`() =
        runTest {
            withAccount()
            val rule =
                TriageRule(
                    0,
                    null,
                    RuleMatcher.Domain("appfigures.com"),
                    VerdictAction.FILE,
                    FolderTarget(FolderRole.REPORTS),
                    1,
                )
            db.learnedRules().put(rule.toEntity())
            db.learnedRules().put(
                rule
                    .copy(
                        action = VerdictAction.DISCARD,
                        target = FolderTarget.Discarded,
                        createdAtMillis = 2,
                    ).toEntity(),
            )
            val rules = db.learnedRules().forAccount("acc-1").map { it.toModel() }
            assertEquals(1, rules.size)
            assertEquals(VerdictAction.DISCARD, rules.single().action)
        }

    @Test
    fun `undo log is purged after its window`() =
        runTest {
            withAccount()

            fun entry(at: Long) =
                UndoEntity(0, "acc-1", "b$at", 1, VerdictAction.DISCARD, "INBOX", 7, 1, "Discarded", 9, 3, "<m1>", at)
            db.undo().insertAll(listOf(entry(100), entry(200), entry(300)))
            assertEquals(2, db.undo().purgeOlderThan(250))
            assertEquals(1, db.undo().batch("acc-1", "b300").size)
        }

    @Test
    fun `schema is exported for migrations`() {
        assertTrue(File("schemas/tech.elephantclock.privatemail.core.data.db.AppDatabase/1.json").exists())
    }

    @Test
    fun `flag bits are pinned to MessageFlag order`() {
        assertEquals(
            listOf("SEEN", "ANSWERED", "FLAGGED", "DRAFT", "DELETED"),
            tech.elephantclock.privatemail.core.model.MessageFlag.entries
                .map { it.name },
        )
        assertEquals(0b10101, setOf(MessageFlagAlias.SEEN, MessageFlagAlias.FLAGGED, MessageFlagAlias.DELETED).toBits())
    }

    @Test
    fun `learned rule scope can't go stale`() {
        val global =
            LearnedRuleEntity(
                accountId = null,
                matcherType = MatcherType.DOMAIN,
                matcherValue = "a.com",
                action = VerdictAction.FILE,
                targetRole = FolderRole.REPORTS,
                targetCustomName = null,
                vip = false,
                createdAtMillis = 1,
            )
        assertEquals("*", global.scope)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { global.copy(accountId = "acc-1") }
    }
}

private typealias MessageFlagAlias = tech.elephantclock.privatemail.core.model.MessageFlag
