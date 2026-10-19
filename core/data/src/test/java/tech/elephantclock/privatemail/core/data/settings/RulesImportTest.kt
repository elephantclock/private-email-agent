@file:Suppress("ktlint:standard:max-line-length")

package tech.elephantclock.privatemail.core.data.settings

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.VerdictAction

/** Export/import of screen 07: a round trip, and files that are malformed, oversized or hostile. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RulesImportTest {
    private val env = RulesTestEnv()
    private val other = AccountId("someone-else")

    @After
    fun tearDown() = env.close()

    private fun parse(text: String) = RulesImport.parse(text.encodeToByteArray(), env.accountId)

    private fun problem(text: String) = (parse(text) as? ImportResult.Invalid)?.problem

    private fun file(body: String) = """{"format":"private-email-agent/rules","version":1$body}"""

    @Test
    fun `an export imports into another account unchanged, apart from what a file may never carry`() =
        runBlocking {
            env.readyForAutoFile()
            env.store.update(env.accountId) {
                it.copy(
                    disabledFolders = setOf(FolderRole.WORK),
                    folderDescriptions = mapOf(FolderRole.PRIORITY to "Outages and anyone waiting on me"),
                    customFolders = listOf(CustomFolder("Partners", "Integrations and resellers", enabled = false)),
                    vips = setOf("boss@corp.example"),
                    alertHigh = false,
                    discardMode = DiscardMode.STRAIGHT_TO_TRASH,
                    trustedAuthservIds = setOf("mx.ourco.example"),
                )
            }
            env.store.setDigest(false)
            env.rule("billing@bank.example", FolderTarget(FolderRole.MONEY))
            env.rule("bd@acme.example", FolderTarget.custom("Partners"))
            val bytes = RulesExport.encode(RulesExport.build(env.db, env.store, env.accountId))

            env.db.accounts().upsert(
                env.db
                    .accounts()
                    .get(env.accountId.value)!!
                    .copy(id = other.value, email = "b@x.example"),
            )
            val plan = (RulesImport.parse(bytes, other) as ImportResult.Ok).plan
            env.store.setDigest(true)
            plan.apply(env.db, env.store, other, trustAuthservIds = true)

            val a = env.store.get(env.accountId)
            val b = env.store.get(other)
            assertEquals(a.disabledFolders, b.disabledFolders)
            assertEquals(a.folderDescriptions, b.folderDescriptions)
            assertEquals(a.customFolders, b.customFolders)
            assertEquals(a.vips, b.vips)
            assertEquals(a.alertHigh, b.alertHigh)
            assertEquals(a.discardMode, b.discardMode)
            assertEquals(a.trustedAuthservIds, b.trustedAuthservIds)
            assertFalse(env.store.digest())
            assertFalse("never carried: the auto-file request", b.autoFileRequested)
            assertTrue("never carried: the backfill cursors", b.backfill.isEmpty())
            assertEquals(OnboardingStep.PERSONALISE, b.step)
            val rules =
                env.db
                    .learnedRules()
                    .forAccount(other.value)
                    .filter { it.accountId == other.value }
            assertEquals(setOf("billing@bank.example", "bd@acme.example"), rules.map { it.matcherValue }.toSet())
        }

    @Test
    fun `an export leaves out rules into custom folders the account no longer has, so it imports`() =
        runBlocking {
            env.readyForAutoFile()
            env.rule("bd@acme.example", FolderTarget.custom("Gone"))
            env.rule("billing@bank.example", FolderTarget(FolderRole.MONEY))
            val bytes = RulesExport.encode(RulesExport.build(env.db, env.store, env.accountId))
            val plan = (RulesImport.parse(bytes, env.accountId) as ImportResult.Ok).plan
            assertEquals(listOf("billing@bank.example"), plan.rules.map { (it.matcher as RuleMatcher.Sender).email })
        }

    @Test
    fun `authserv-ids are applied only when the user confirms them`() =
        runBlocking {
            env.readyForAutoFile()
            val plan =
                (parse(file(""","trustedAuthservIds":["mx.evil.example"]""")) as ImportResult.Ok).plan
            assertEquals(setOf("mx.evil.example"), plan.authservIds)
            plan.apply(env.db, env.store, env.accountId, trustAuthservIds = false)
            assertTrue(
                env.store
                    .get(env.accountId)
                    .trustedAuthservIds
                    .isEmpty(),
            )
        }

    @Test
    fun `a file with backfill or auto-file keys is refused and the gate stays as it was`() =
        runBlocking {
            env.db.accounts().upsert(env.server.account.toEntity(createdAtMillis = 1))
            env.store.update(env.accountId) { it.copy(autoFileRequested = true) }
            assertNotEquals(BackfillStatus.COMPLETE, env.gate.status(env.accountId))
            val hostile =
                file(""","backfill":{"Sent":{"uidValidity":1,"done":true}},"autoFileRequested":true""")
            assertEquals(ImportProblem.NOT_JSON, problem(hostile))
            assertNotEquals(BackfillStatus.COMPLETE, env.gate.status(env.accountId))
            assertTrue(
                env.store
                    .get(env.accountId)
                    .backfill
                    .isEmpty(),
            )
        }

    @Test
    fun `malformed, oversized and wrongly typed files are refused`() {
        assertEquals(ImportProblem.NOT_JSON, problem("{not json"))
        assertEquals(ImportProblem.NOT_JSON, problem(""))
        assertEquals(ImportProblem.WRONG_FORMAT, problem("""{"format":"something-else","version":1}"""))
        assertEquals(ImportProblem.NEWER_VERSION, problem("""{"format":"private-email-agent/rules","version":99}"""))
        assertEquals(ImportProblem.NOT_JSON, problem(file(""","vips":"boss@corp.example"""")))
        assertEquals(ImportProblem.NOT_JSON, problem(file(""","alertUrgent":"yes"""")))
        assertEquals(ImportProblem.NOT_JSON, problem(file(""","discardMode":"DELETE_FOREVER"""")))
        val tooBig = ByteArray(RulesImport.MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(
            ImportProblem.TOO_LARGE,
            (RulesImport.parse(tooBig, env.accountId) as ImportResult.Invalid).problem,
        )
        val invalidUtf8 = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), '}'.code.toByte())
        assertEquals(
            ImportProblem.NOT_JSON,
            (RulesImport.parse(invalidUtf8, env.accountId) as ImportResult.Invalid).problem,
        )
    }

    @Test
    fun `deep nesting and huge strings are refused without blowing up`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(ImportProblem.NOT_JSON, problem(file(""","vips":$deep""")))
        assertEquals(ImportProblem.NOT_JSON, problem(deep))
        val huge = "a".repeat(200_000)
        assertEquals(ImportProblem.BAD_ADDRESS, problem(file(""","vips":["$huge@x.example"]""")))
        assertEquals(
            ImportProblem.BAD_FOLDER,
            problem(file(""","customFolders":[{"name":"Partners","description":"${"d".repeat(5_000)}"}]""")),
        )
        val many =
            (1..RulesImport.MAX_RULES + 1).joinToString(",") {
                """{"matcher":"sender","value":"a$it@x.example","target":"MONEY"}"""
            }
        assertEquals(ImportProblem.TOO_MANY, problem(file(""","rules":[$many]""")))
    }

    @Test
    fun `hostile rules and folder names are refused`() {
        fun rule(json: String) = problem(file(""","rules":[$json]"""))
        assertEquals(
            ImportProblem.BAD_RULE,
            rule("""{"matcher":"sender","value":"x@spam.example","target":"DISCARDED","vip":true}"""),
        )
        assertEquals(ImportProblem.BAD_RULE, rule("""{"matcher":"domain","value":"gmail.com","target":"DISCARDED"}"""))
        assertEquals(ImportProblem.BAD_RULE, rule("""{"matcher":"domain","value":"mail.gmail.com","target":"JUNK"}"""))
        assertEquals(ImportProblem.BAD_RULE, rule("""{"matcher":"regex","value":".*","target":"DISCARDED"}"""))
        assertEquals(ImportProblem.BAD_RULE, rule("""{"matcher":"sender","value":"not an address","target":"MONEY"}"""))
        assertEquals(ImportProblem.BAD_RULE, rule("""{"matcher":"sender","value":"X@Y.example","target":"MONEY"}"""))
        assertEquals(
            ImportProblem.BAD_RULE,
            rule("""{"matcher":"sender","value":"a@b.example","target":"CUSTOM","customName":"Nowhere"}"""),
        )
        assertEquals(
            ImportProblem.BAD_RULE,
            rule("""{"matcher":"sender","value":"a@b.example","target":"MONEY","customName":"x"}"""),
        )
        assertEquals(ImportProblem.NOT_JSON, rule("""{"matcher":"sender","value":"a@b.example","target":"TRASH"}"""))

        fun folder(name: String) = problem(file(""","customFolders":[{"name":"$name","description":"d"}]"""))
        assertEquals(ImportProblem.BAD_FOLDER, folder("../INBOX"))
        assertEquals(ImportProblem.BAD_FOLDER, folder("Work/Secret"))
        assertEquals(ImportProblem.BAD_FOLDER, folder("*"))
        assertEquals(ImportProblem.BAD_FOLDER, folder("Trash"))
        assertEquals(ImportProblem.BAD_FOLDER, folder("a\\u0000b"))
        assertEquals(
            ImportProblem.BAD_FOLDER,
            problem(file(""","customFolders":[{"name":"P","description":"d"},{"name":"p","description":"d"}]""")),
        )
        assertEquals(ImportProblem.BAD_FOLDER, problem(file(""","folders":[{"role":"JUNK"}]""")))
        assertEquals(ImportProblem.BAD_AUTHSERV_ID, problem(file(""","trustedAuthservIds":["mx; dkim=pass"]""")))
    }

    @Test
    fun `a valid VIP rule keeps in Priority and imported rules belong to the chosen account`() {
        val plan =
            (
                parse(
                    file(
                        ""","rules":[{"matcher":"sender","value":"ceo@corp.example","target":"PRIORITY","vip":true},""" +
                            """{"matcher":"domain","value":"promo.example","target":"DISCARDED"}]""",
                    ),
                ) as ImportResult.Ok
            ).plan
        val vip = plan.rules.first()
        assertTrue(vip.vip)
        assertEquals(VerdictAction.KEEP, vip.action)
        assertEquals(env.accountId, vip.accountId)
        assertEquals(RuleMatcher.Domain("promo.example"), plan.rules[1].matcher)
        assertEquals(VerdictAction.DISCARD, plan.rules[1].action)
    }

    @Test
    fun `retargeting a learned rule keeps its invariants and never changes a VIP rule`() =
        runBlocking {
            env.readyForAutoFile()
            env.rule("billing@bank.example", FolderTarget(FolderRole.MONEY))
            val edits = LearnedRuleEdits(env.db)
            val id =
                env.db
                    .learnedRules()
                    .forAccount(env.accountId.value)
                    .single()
                    .id
            assertEquals(RuleEditResult.DONE, edits.retarget(id, FolderTarget.Discarded))
            val changed =
                env.db
                    .rules()
                    .rule(id)!!
                    .toModel()
            assertEquals(VerdictAction.DISCARD, changed.action)

            val vip =
                (
                    parse(
                        file(
                            ""","rules":[{"matcher":"sender","value":"ceo@corp.example","target":"PRIORITY","vip":true}]""",
                        ),
                    ) as ImportResult.Ok
                ).plan
            vip.apply(env.db, env.store, env.accountId, trustAuthservIds = false)
            val vipId =
                env.db
                    .learnedRules()
                    .forAccount(env.accountId.value)
                    .first { it.vip }
                    .id
            assertEquals(RuleEditResult.VIP_RULE, edits.retarget(vipId, FolderTarget.Discarded))
            assertEquals(
                VerdictAction.KEEP,
                env.db
                    .rules()
                    .rule(vipId)!!
                    .action,
            )
            edits.delete(vipId)
            assertEquals(null, env.db.rules().rule(vipId))
            assertEquals(RuleEditResult.GONE, edits.retarget(vipId, FolderTarget.Discarded))
        }
}
