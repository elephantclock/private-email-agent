package tech.elephantclock.privatemail.core.triage.rules

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.triage.MessageGenerator
import tech.elephantclock.privatemail.core.triage.TestMail
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor

/**
 * Quality bar: rules ≤ 5 ms per email. Signals and rules for 1000 messages, with 500 frequent
 * domains and 300 learned rules, must average well under that on a JVM; the bound is
 * generous (the whole batch under 5 s) so the test isn't flaky on a busy CI machine.
 */
class RulesTimingTest {
    @Test
    fun `signals and rules take under 5 ms per message`() {
        val gen = MessageGenerator(seed = 5)
        val frequent = List(500) { "partner$it-company.example" } + gen.index.frequentDomains()
        val learned =
            LearnedRules(
                List(300) {
                    LearnedRules.fromCorrection(
                        Correction(
                            null,
                            Address.of("x@sender$it.example"),
                            FolderTarget(FolderRole.REPORTS),
                            Correction.Scope.DOMAIN,
                        ),
                        it.toLong(),
                        1,
                    )
                },
            )
        val engine = RulesEngine(TestMail.account, gen.protections, SignalExtractor(frequent), learned)
        val messages = List(1_000) { gen.next().message }
        repeat(3) { messages.forEach(engine::decide) } // warm-up
        val start = System.nanoTime()
        messages.forEach(engine::decide)
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        println("rules: %.3f ms per message".format(totalMs / messages.size))
        assertTrue(totalMs < 5_000, "1000 messages took $totalMs ms")
    }
}
