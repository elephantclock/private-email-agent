package tech.elephantclock.privatemail.core.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

class ClassificationTest {
    @Test
    fun `priority maps to keep with the model's importance, marked AI`() {
        val v = TestData.classification("priority", Importance.URGENT, 0.95f).toVerdict()
        assertEquals(VerdictAction.KEEP, v.action)
        assertEquals(Importance.URGENT, v.importance)
        assertEquals(VerdictSource.AI, v.source)
        assertEquals(0.95f, v.confidence)
        assertEquals("sum", v.summary)
    }

    @Test
    fun `value and custom folders map to file`() {
        val money = TestData.classification("money", Importance.HIGH).toVerdict()
        assertEquals(VerdictAction.FILE, money.action)
        assertEquals(FolderRole.MONEY, money.target.role)
        assertEquals(Importance.HIGH, money.importance)
        val custom = TestData.classification("custom-1").toVerdict()
        assertEquals(FolderTarget.custom("Kids school"), custom.target)
    }

    @Test
    fun `a confident discard or junk is low importance even if the model said urgent`() {
        val d = TestData.classification("discarded", Importance.URGENT, 0.9f).toVerdict()
        assertEquals(VerdictAction.DISCARD, d.action)
        assertEquals(Importance.LOW, d.importance)
        val j = TestData.classification("junk", Importance.HIGH, 0.99f).toVerdict()
        assertEquals(VerdictAction.JUNK, j.action)
        assertEquals(Importance.LOW, j.importance)
    }

    @Test
    fun `an unsure discard is kept, normal importance`() {
        val v = TestData.classification("discarded", confidence = 0.6f).toVerdict()
        assertEquals(VerdictAction.KEEP, v.action)
        assertEquals(Importance.NORMAL, v.importance)
        assertTrue(v.reason, v.reason.startsWith("Kept: AI unsure (60%)"))
        assertEquals(
            VerdictAction.JUNK,
            TestData.classification("junk", confidence = 0.6f).toVerdict(AiVerdictPolicy(0.5f)).action,
        )
    }

    @Test
    fun `out-of-range confidence, blank reason and long multi-line text are normalised`() {
        val raw =
            AiClassification(
                FolderTarget.Priority,
                Importance.LOW,
                7f,
                " \n ",
                "line one\nline two" + "x".repeat(500),
            )
        val v = raw.toVerdict()
        assertEquals(1f, v.confidence)
        assertEquals("AI: priority", v.reason)
        assertTrue(v.summary!!.startsWith("line one line two"))
        assertEquals(AiLimits.SUMMARY_CHARS, v.summary!!.length)
        assertEquals(0f, raw.copy(confidence = Float.NaN).toVerdict().confidence)
    }

    @Test
    fun `classify item keys on Message-ID, else on the server ref, and caps text`() {
        val h = TestData.header(uid = 9)
        assertEquals("<m9@example.com>", ClassifyItem.keyOf(h))
        assertEquals("ref:acc/INBOX/3/9", ClassifyItem.keyOf(h.copy(messageId = null)))
        assertEquals("ref:acc/INBOX/3/9", ClassifyItem.keyOf(h.copy(messageId = " ")))
        val item = ClassifyItem.from(h.copy(subject = "s".repeat(1000)), TestData.body(h, "b".repeat(10_000)))
        assertEquals(AiLimits.SUBJECT_CHARS, item.subject.length)
        assertEquals(AiLimits.PREVIEW_CHARS, item.preview.length)
        assertEquals("", ClassifyItem.from(h, null).preview)
    }

    @Test
    fun `requests reject duplicate keys and odd key syntax`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassifyRequest(TestData.folders + TestData.folders.first(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FolderOption("bad key\"", FolderTarget.Priority, "P", "d")
        }
    }

    @Test
    fun `classifyOne gives a verdict, or null for rules fallback`() =
        runTest {
            val availability = FakeAiAvailability()
            val engine = FakeAiEngine(availability)
            val item = ClassifyItem.from(TestData.header(), null)
            engine.script(item.key, AiResult.Ok(TestData.classification("money")))
            val verdict = engine.classifyOne(TestData.folders, item).toVerdictOrNull()
            assertEquals(FolderRole.MONEY, verdict!!.target.role)

            availability.set(AiFeature.PROMPT, ModelStatus.Downloadable())
            assertNull(engine.classifyOne(TestData.folders, item).toVerdictOrNull())
        }
}
