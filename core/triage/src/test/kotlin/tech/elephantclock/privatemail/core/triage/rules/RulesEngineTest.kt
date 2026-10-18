package tech.elephantclock.privatemail.core.triage.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictAction.DISCARD
import tech.elephantclock.privatemail.core.model.VerdictAction.FILE
import tech.elephantclock.privatemail.core.model.VerdictAction.JUNK
import tech.elephantclock.privatemail.core.model.VerdictAction.KEEP
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.TestMail
import tech.elephantclock.privatemail.core.triage.TestMail.h
import tech.elephantclock.privatemail.core.triage.TestMail.mail
import tech.elephantclock.privatemail.core.triage.people.Protections
import tech.elephantclock.privatemail.core.triage.people.RelationshipIndex
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor

class RulesEngineTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("tech.elephantclock.privatemail.core.triage.rules.RuleCases#cases")
    fun `rule, action and explain string`(
        name: String,
        message: MailItem,
        rule: String,
        action: VerdictAction?,
        reason: String,
    ) {
        val d = RuleCases.engine.decide(message)
        assertEquals(rule, d.rule, name)
        assertEquals(reason, d.reason, name)
        assertEquals(action, (d as? Decision.Final)?.verdict?.action, name)
    }

    @Test
    fun `decisions are deterministic`() {
        val messages = RuleCases.cases().map { it.get()[1] as MailItem }
        val first = messages.map { RuleCases.engine.decide(it).reason }
        repeat(3) { assertEquals(first, messages.map { RuleCases.engine.decide(it).reason }) }
    }

    @Test
    fun `importance and folder of role verdicts`() {
        fun verdict(m: MailItem) = (RuleCases.engine.decide(m) as Decision.Final).verdict
        assertEquals(Importance.URGENT, verdict(mail(from = "boss@company.example")).importance)
        assertEquals(Importance.HIGH, verdict(mail(subject = "Your verification code is 123456")).importance)
        assertEquals(
            FolderTarget(FolderRole.REPORTS),
            verdict(mail(from = "noreply@monitor.example", subject = "Daily report")).target,
        )
        assertEquals(
            Importance.LOW,
            verdict(mail(from = "noreply@monitor.example", subject = "Daily report")).importance,
        )
        assertEquals(FolderTarget.custom("Clients"), verdict(mail(from = "x@client.example")).target)
    }

    @Test
    fun `needs-ai accept applies the protection to the AI verdict`() {
        val d = RuleCases.engine.decide(mail(from = "anna@example.org", subject = "Lunch?")) as Decision.NeedsAi
        val ai =
            tech.elephantclock.privatemail.core.model.Verdict.discard(
                tech.elephantclock.privatemail.core.model.VerdictSource.AI,
                "Looks like a newsletter",
                0.6f,
            )
        assertEquals(KEEP, d.accept(ai).action)
        val stranger =
            RuleCases.engine.decide(
                mail(from = "x@stranger.example", subject = "Lunch?"),
            ) as Decision.NeedsAi
        assertEquals(DISCARD, stranger.accept(ai).action)
    }
}
