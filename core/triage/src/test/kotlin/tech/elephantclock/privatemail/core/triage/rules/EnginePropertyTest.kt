package tech.elephantclock.privatemail.core.triage.rules

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MessageGenerator
import tech.elephantclock.privatemail.core.triage.TestMail
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor

/**
 * Property over the whole engine, with hostile learned rules (discard and junk the domains of
 * people the user writes to) and an AI that always says discard or junk: no protected message
 * ends up discarded, and no relationship-protected one junked.
 */
class EnginePropertyTest {
    @Test
    fun `no protected message gets discarded and no person the user wrote to gets junked`() {
        val gen = MessageGenerator(seed = 99)
        val hostileRules =
            (gen.people + gen.contacts + gen.vips).flatMapIndexed { i, email ->
                val target = if (i % 2 == 0) FolderTarget.Discarded else FolderTarget.Junk
                val scope = if (i % 3 == 0) Correction.Scope.SENDER else Correction.Scope.DOMAIN
                listOf(LearnedRules.fromCorrection(Correction(null, Address.of(email), target, scope), i.toLong(), 1))
            } +
                listOf("shop.example", "service.example", "mailer.example", "stranger.example").mapIndexed { i, d ->
                    LearnedRules.fromCorrection(
                        Correction(
                            TestMail.account,
                            Address.of("x@$d"),
                            FolderTarget.Discarded,
                            Correction.Scope.DOMAIN,
                        ),
                        1_000L + i,
                        1,
                    )
                }
        val engine =
            RulesEngine(
                TestMail.account,
                gen.protections,
                SignalExtractor(gen.index.frequentDomains()),
                LearnedRules(hostileRules),
            )
        val aiProposals =
            listOf(
                Verdict.discard(VerdictSource.AI, "AI says discard", 0.99f),
                Verdict.junk(VerdictSource.AI, "AI says junk", 0.99f),
            )
        val counts = HashMap<String, Int>()
        repeat(10_000) { n ->
            val g = gen.next()
            val decision = engine.decide(g.message)
            counts.merge(decision.rule.substringBefore(':'), 1, Int::plus)
            val verdict =
                when (decision) {
                    is Decision.Final -> decision.verdict
                    is Decision.NeedsAi -> decision.accept(aiProposals[n % 2])
                }
            val protection = decision.protection
            if (g.protected) assertTrue(protection.blocksDiscard, "protection missed: ${g.message.header}")
            if (g.relationship) assertTrue(protection.relationship, "relationship missed: ${g.message.header}")
            if (protection.blocksDiscard) {
                assertNotEquals(VerdictAction.DISCARD, verdict.action, "${decision.rule}: ${g.message.header}")
            }
            if (protection.relationship) {
                assertNotEquals(VerdictAction.JUNK, verdict.action, "${decision.rule}: ${g.message.header}")
            }
        }
        // The run must have exercised the dangerous paths, not just needs-ai.
        for (rule in listOf("learned", "phishing.lookalike", "bulk.marketing", "needs-ai", "vip")) {
            assertTrue((counts[rule] ?: 0) > 0, "rule $rule never fired: $counts")
        }
    }
}
