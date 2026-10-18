package tech.elephantclock.privatemail.core.triage.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tech.elephantclock.privatemail.core.model.AuthResult
import tech.elephantclock.privatemail.core.model.AuthResult.FAIL
import tech.elephantclock.privatemail.core.model.AuthResult.NEUTRAL
import tech.elephantclock.privatemail.core.model.AuthResult.NONE
import tech.elephantclock.privatemail.core.model.AuthResult.PASS
import tech.elephantclock.privatemail.core.model.AuthResult.PERMERROR
import tech.elephantclock.privatemail.core.model.AuthResult.SOFTFAIL
import tech.elephantclock.privatemail.core.model.AuthResult.TEMPERROR
import tech.elephantclock.privatemail.core.model.AuthResult.UNKNOWN
import tech.elephantclock.privatemail.core.model.AuthResults

class AuthResultsParserTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("singleHeaders")
    fun `parses one header`(
        value: String,
        expected: AuthResults,
    ) {
        assertEquals(expected, AuthResultsParser.parse(listOf(value)))
    }

    @Test
    fun `only the topmost instance counts and forged lower ones are ignored`() {
        // The same pair as SyncFixtures.twoAuthResults in core:mail, in the order T12 stores them.
        val instances = listOf("mx.localhost; dmarc=fail", "forged.example; dmarc=pass")
        assertEquals(AuthResults(dmarc = FAIL), AuthResultsParser.parse(instances))
    }

    @Test
    fun `a forged pass below cannot override a real fail`() {
        val instances =
            listOf(
                "mx.izi.travel; spf=fail smtp.mailfrom=evil.example; dkim=none; dmarc=fail header.from=paypal.com",
                "mx.izi.travel; spf=pass; dkim=pass; dmarc=pass",
            )
        assertEquals(AuthResults(FAIL, NONE, FAIL), AuthResultsParser.parse(instances))
    }

    @Test
    fun `with a configured authserv-id only matching instances count`() {
        val instances =
            listOf(
                "relay.other.example; spf=pass; dkim=pass; dmarc=pass",
                "MX.IZI.TRAVEL; spf=softfail; dmarc=fail",
                "mx.izi.travel; dmarc=pass",
            )
        assertEquals(
            AuthResults(spf = SOFTFAIL, dmarc = FAIL),
            AuthResultsParser.parse(instances, setOf("mx.izi.travel")),
        )
    }

    @Test
    fun `a configured authserv-id with no matching instance gives unknown, not the topmost`() {
        val instances = listOf("attacker.example; spf=pass; dkim=pass; dmarc=pass")
        assertEquals(AuthResults(), AuthResultsParser.parse(instances, setOf("mx.izi.travel")))
    }

    @Test
    fun `a topmost instance without an authserv-id does not fall back to lower ones`() {
        val instances = listOf("spf=fail", "forged.example; dmarc=pass")
        assertEquals(AuthResults(), AuthResultsParser.parse(instances))
    }

    @Test
    fun `missing header gives unknown`() {
        assertEquals(AuthResults(), AuthResultsParser.parse(null))
        assertEquals(AuthResults(), AuthResultsParser.parse(emptyList()))
    }

    @ParameterizedTest
    @MethodSource("garbage")
    fun `garbage never throws`(value: String) {
        AuthResultsParser.parse(listOf(value))
        AuthResultsParser.parse(listOf(value, value), setOf("mx.izi.travel"))
    }

    @Test
    fun `huge hostile input is fast`() {
        val hostile = "a; " + "((((((((((".repeat(20_000) + "\"\\".repeat(20_000) + "spf=pass;".repeat(20_000)
        val start = System.nanoTime()
        repeat(20) { AuthResultsParser.parse(List(100) { hostile }, setOf("x")) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assert(ms < 2_000) { "took $ms ms" }
    }

    companion object {
        private fun r(
            spf: AuthResult = UNKNOWN,
            dkim: AuthResult = UNKNOWN,
            dmarc: AuthResult = UNKNOWN,
        ) = AuthResults(spf, dkim, dmarc)

        @JvmStatic
        fun singleHeaders() =
            listOf(
                Arguments.of(
                    "mx.google.com; spf=pass smtp.mailfrom=a.example; dkim=pass header.d=a.example; dmarc=pass",
                    r(PASS, PASS, PASS),
                ),
                Arguments.of("mx.example.com 1; none", r()),
                Arguments.of(
                    "mx.example.com;\r\n\tspf=softfail (sender not permitted) smtp.mailfrom=x;\r\n dmarc=fail (p=REJECT)",
                    r(SOFTFAIL, UNKNOWN, FAIL),
                ),
                Arguments.of("mx.example.com; dkim=fail header.d=a; dkim=pass header.d=b", r(dkim = PASS)),
                Arguments.of("mx.example.com; dkim=fail header.d=a; dkim=neutral header.d=b", r(dkim = FAIL)),
                Arguments.of("mx.example.com; spf = hardfail ; dmarc = permerror", r(spf = FAIL, dmarc = PERMERROR)),
                Arguments.of("mx.example.com; SPF=PASS; DKIM/1=TempError; DMARC=None", r(PASS, TEMPERROR, NONE)),
                Arguments.of("mx.example.com; dkim=policy; spf=neutral", r(NEUTRAL, NEUTRAL)),
                Arguments.of("mx.example.com; spf=bestguesspass", r()),
                // A semicolon inside a comment or quoted string does not start a new result.
                Arguments.of("mx.example.com; spf=fail (x; dmarc=pass) smtp.mailfrom=x", r(spf = FAIL)),
                Arguments.of("mx.example.com; spf=fail reason=\"a; dmarc=pass\"", r(spf = FAIL)),
                Arguments.of("mx.example.com; spf=fail (nested (a; dmarc=pass) b; dkim=pass)", r(spf = FAIL)),
                Arguments.of("mx.example.com; spf=fail; spf=pass", r(spf = FAIL)),
                Arguments.of("(comment) mx.example.com; dmarc=pass", r(dmarc = PASS)),
            )

        @JvmStatic
        fun garbage() =
            listOf(
                "",
                ";;;;",
                "=",
                "((((",
                "))))",
                "\"",
                "\\",
                "mx; spf=",
                "mx; =pass",
                "mx; spf==pass",
                "\uD800\uDC00\uD800",
                "mx;\u0000spf=pass",
                "x".repeat(100_000),
                ";".repeat(100_000),
                "mx; " + "dkim=pass; ".repeat(10_000),
            )
    }
}
