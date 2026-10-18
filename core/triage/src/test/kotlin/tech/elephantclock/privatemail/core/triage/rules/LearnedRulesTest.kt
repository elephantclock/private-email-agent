package tech.elephantclock.privatemail.core.triage.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

class LearnedRulesTest {
    private val acc = AccountId("acc-1")
    private val other = AccountId("acc-2")
    private val sender = Address.of("news@mail.shop.example")

    private fun correction(
        target: FolderTarget,
        scope: Correction.Scope = Correction.Scope.SENDER,
        from: Address = sender,
        vip: Boolean = false,
        account: AccountId? = acc,
    ) = LearnedRules.fromCorrection(Correction(account, from, target, scope, vip), id = 1, nowMillis = 100)

    @Test
    fun `corrections become rules`() {
        assertEquals(
            TriageRule(
                1,
                acc,
                RuleMatcher.Sender("news@mail.shop.example"),
                VerdictAction.DISCARD,
                FolderTarget.Discarded,
                100,
            ),
            correction(FolderTarget.Discarded),
        )
        assertEquals(
            TriageRule(
                1,
                acc,
                RuleMatcher.Domain("mail.shop.example"),
                VerdictAction.FILE,
                FolderTarget(FolderRole.ORDERS),
                100,
            ),
            correction(FolderTarget(FolderRole.ORDERS), Correction.Scope.DOMAIN),
        )
        assertEquals(VerdictAction.KEEP, correction(FolderTarget.Priority).action)
        assertEquals(VerdictAction.JUNK, correction(FolderTarget.Junk).action)
        assertEquals(VerdictAction.FILE, correction(FolderTarget.custom("Clients")).action)
    }

    @Test
    fun `a domain correction on free mail becomes a sender rule`() {
        for (email in listOf("anna@gmail.com", "ivan@mail.ru", "x@yandex.ru", "y@yahoo.co.uk", "z@nodot")) {
            val rule = correction(FolderTarget.Discarded, Correction.Scope.DOMAIN, Address.of(email))
            assertEquals(RuleMatcher.Sender(email), rule.matcher, email)
        }
    }

    @Test
    fun `a VIP correction always keeps in Priority`() {
        val rule = correction(FolderTarget.Discarded, vip = true)
        assertEquals(true, rule.vip)
        assertEquals(VerdictAction.KEEP, rule.action)
        assertEquals(FolderTarget.Priority, rule.target)
    }

    @Test
    fun `the most specific rule wins`() {
        fun r(
            id: Long,
            matcher: RuleMatcher,
            account: AccountId? = acc,
            at: Long = 1,
        ) = TriageRule(id, account, matcher, VerdictAction.FILE, FolderTarget(FolderRole.WORK), at)
        val rules =
            LearnedRules(
                listOf(
                    r(1, RuleMatcher.Domain("example")),
                    r(2, RuleMatcher.Domain("shop.example")),
                    r(3, RuleMatcher.Domain("mail.shop.example"), account = null),
                    r(4, RuleMatcher.Domain("mail.shop.example")),
                    r(5, RuleMatcher.Sender("news@mail.shop.example"), at = 1),
                    r(6, RuleMatcher.Sender("news@mail.shop.example"), at = 2),
                    r(7, RuleMatcher.Sender("news@mail.shop.example"), account = other, at = 3),
                ),
            )
        assertEquals(6L, rules.match(acc, sender)?.id)
        assertEquals(4L, rules.match(acc, Address.of("other@mail.shop.example"))?.id)
        assertEquals(3L, rules.match(other, Address.of("other@mail.shop.example"))?.id)
        assertEquals(2L, rules.match(acc, Address.of("a@shop.example"))?.id)
        assertEquals(1L, rules.match(acc, Address.of("a@else.example"))?.id)
        assertNull(rules.match(acc, Address.of("a@unrelated.test")))
        assertNull(LearnedRules.EMPTY.match(acc, sender))
    }

    @Test
    fun `upsert replaces the same matcher and account`() {
        val first = correction(FolderTarget.Discarded)
        val second = correction(FolderTarget(FolderRole.ORDERS)).copy(id = 2)
        val globalOne = correction(FolderTarget.Junk, account = null).copy(id = 3)
        val rules = LearnedRules.upsert(LearnedRules.upsert(listOf(first), globalOne), second)
        assertEquals(listOf(3L, 2L), rules.map { it.id })
    }

    @Test
    fun `explain strings are stable`() {
        val s = VerdictSource.RULE
        assertEquals(
            Verdict.discard(s, "Your rule: discard mail from news@mail.shop.example"),
            LearnedRules.verdict(correction(FolderTarget.Discarded)),
        )
        assertEquals(
            Verdict.file(
                FolderTarget(FolderRole.ORDERS),
                Importance.NORMAL,
                s,
                "Your rule: mail from mail.shop.example goes to Orders & travel",
            ),
            LearnedRules.verdict(correction(FolderTarget(FolderRole.ORDERS), Correction.Scope.DOMAIN)),
        )
        assertEquals(
            Verdict.keep(Importance.NORMAL, s, "Your rule: keep mail from news@mail.shop.example in Priority"),
            LearnedRules.verdict(correction(FolderTarget.Priority)),
        )
        assertEquals(
            Verdict.junk(s, "Your rule: mail from news@mail.shop.example is junk"),
            LearnedRules.verdict(correction(FolderTarget.Junk)),
        )
        assertEquals(
            Verdict.keep(Importance.URGENT, s, "Your rule: news@mail.shop.example is a VIP"),
            LearnedRules.verdict(correction(FolderTarget.Priority, vip = true)),
        )
        assertEquals(
            listOf(
                "Priority",
                "People",
                "Money",
                "Work updates",
                "Orders & travel",
                "Reports & alerts",
                "Discarded",
                "Junk",
                "Clients",
            ),
            listOf(
                FolderTarget.Priority,
                FolderTarget(FolderRole.PEOPLE),
                FolderTarget(FolderRole.MONEY),
                FolderTarget(FolderRole.WORK),
                FolderTarget(FolderRole.ORDERS),
                FolderTarget(FolderRole.REPORTS),
                FolderTarget.Discarded,
                FolderTarget.Junk,
                FolderTarget.custom("Clients"),
            ).map { it.label() },
        )
    }
}
