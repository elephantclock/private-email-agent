package tech.elephantclock.privatemail.core.triage.people

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MessageGenerator
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor
import kotlin.random.Random

/**
 * Property: whatever verdict anything proposes, once the message's protection is enforced a
 * protected message is never discarded, and a relationship-protected one is never junked.
 */
class ProtectionPropertyTest {
    private val proposals =
        listOf(
            Verdict.discard(VerdictSource.RULE, "bulk"),
            Verdict.discard(VerdictSource.AI, "low value", 0.4f),
            Verdict.discard(VerdictSource.USER, "learned rule"),
            Verdict.junk(VerdictSource.RULE, "phishing"),
            Verdict.junk(VerdictSource.AI, "spam", 0.9f),
            Verdict.keep(Importance.HIGH, VerdictSource.AI, "important"),
            Verdict.file(FolderTarget(FolderRole.REPORTS), Importance.LOW, VerdictSource.RULE, "report"),
        )

    @Test
    fun `no protected message can end up discarded, and no relationship-protected one junked`() {
        val gen = MessageGenerator(seed = 2026)
        val extractor = SignalExtractor(gen.index.frequentDomains())
        val rnd = Random(7)
        var protectedCount = 0
        var relationshipCount = 0
        repeat(5_000) {
            val g = gen.next()
            val signals = extractor.extract(g.message)
            val protection = gen.protections.evaluate(g.message, signals)
            // The oracle: whatever the generator put in must be detected.
            if (g.relationship) assertTrue(protection.relationship, "missed relationship: ${g.message.header}")
            if (g.content) assertTrue(protection.blocksDiscard, "missed content: ${g.message.header.subject}")
            if (protection.blocksDiscard) protectedCount++
            if (protection.relationship) relationshipCount++

            val proposed = proposals[rnd.nextInt(proposals.size)]
            val result = protection.enforce(proposed)
            if (protection.blocksDiscard) assertNotEquals(VerdictAction.DISCARD, result.action, "${g.message.header}")
            if (protection.relationship) assertNotEquals(VerdictAction.JUNK, result.action, "${g.message.header}")
            if (!protection.forbids(proposed.action)) assertEquals(proposed, result)
        }
        // The generator must actually exercise both sides.
        assertTrue(protectedCount in 1_000..4_900, "protected: $protectedCount")
        assertTrue(relationshipCount in 1_000..4_900, "relationship: $relationshipCount")
    }
}
