package tech.elephantclock.privatemail.core.triage.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import tech.elephantclock.privatemail.core.triage.rules.Decision
import java.io.File

/**
 * Golden triage: every fixture in `fixtures/` must get the decision in `expected.json`
 * (action, target, importance and rule id, or `needs-ai` with its protection).
 *
 * `expected.json` says what is **right**, written by hand; never regenerate it from the engine's
 * output. A case the engine still gets wrong is listed under `knownFailures` with the reason;
 * such a case that starts passing fails the test until it is taken off the list.
 */
class GoldenTriageTest {
    private val context = GoldenContext.load()
    private val root = Json.parseToJsonElement(File(GoldenContext.dir, "expected.json").readText()).jsonObject
    private val expected: Map<String, Map<String, String>> =
        root.getValue("fixtures").jsonObject.mapValues { (_, v) -> v.jsonObject.fields() }
    private val knownFailures: Map<String, String> =
        root["knownFailures"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    @Test
    fun `every fixture has an expectation and every expectation a fixture`() {
        val files = GoldenContext.fixtureFiles().map { it.name }.toSet()
        assertEquals(emptySet<String>(), files - expected.keys, "fixtures without an entry in expected.json")
        assertEquals(emptySet<String>(), expected.keys - files, "entries in expected.json without a fixture")
        assertEquals(emptySet<String>(), knownFailures.keys - files, "known failures without a fixture")
        assertTrue(files.size >= 80, "the corpus should keep about 80 fixtures, has ${files.size}")
    }

    @Test
    fun `every decision matches expected json`() {
        val mismatches = mutableListOf<String>()
        val stillFailing = mutableListOf<String>()
        val fixed = mutableListOf<String>()
        for ((name, message) in context.fixtures()) {
            val want = expected[name] ?: continue
            val got = describe(context.engine.decide(message))
            val ok = want == got
            val known = knownFailures[name]
            when {
                ok && known != null -> fixed += name
                !ok && known != null -> stillFailing += "$name (known: $known)\n${diff(want, got)}"
                !ok -> mismatches += "$name\n${diff(want, got)}"
            }
        }
        if (stillFailing.isNotEmpty()) {
            println("Known failures (${stillFailing.size}):\n" + stillFailing.joinToString("\n"))
        }
        val report =
            buildList {
                if (mismatches.isNotEmpty()) add("${mismatches.size} mismatch(es):\n" + mismatches.joinToString("\n"))
                if (fixed.isNotEmpty()) add("Now passing; remove from knownFailures: ${fixed.joinToString()}")
            }
        if (report.isNotEmpty()) fail(report.joinToString("\n\n"))
    }

    @Test
    fun `parser decodes encoded-word headers exactly`() {
        val byName = context.fixtures().toMap()
        assertEquals("Отчёт по проекту до пятницы?", byName.getValue("01-people-ru-colleague.eml").header.subject)
        val disguised = byName.getValue("68-phish-hidden-brand-name.eml").header.from!!
        assertEquals("Pay​Pal Service", disguised.name)
        val forged = byName.getValue("71-phish-forged-lower-auth-results.eml").header.headers
        assertEquals(2, forged.getValue("authentication-results").size)
    }

    private fun describe(d: Decision): Map<String, String> =
        when (d) {
            is Decision.NeedsAi -> {
                mapOf("decision" to "needs-ai", "protected" to d.protection.blocksDiscard.toString())
            }

            is Decision.Final -> {
                buildMap {
                    put("action", d.verdict.action.name)
                    put("target", d.verdict.target.role.name)
                    d.verdict.target.customName
                        ?.let { put("customName", it) }
                    put("importance", d.verdict.importance.name)
                    put("rule", d.rule)
                }
            }
        }

    private fun diff(
        want: Map<String, String>,
        got: Map<String, String>,
    ): String =
        (want.keys + got.keys).filter { want[it] != got[it] }.joinToString("\n") {
            "    $it: expected ${want[it] ?: "-"}, got ${got[it] ?: "-"}"
        }

    /** The comparable fields of an expectation; `note` is documentation only. */
    private fun JsonObject.fields(): Map<String, String> =
        filterKeys { it != "note" }.mapValues { it.value.jsonPrimitive.content }
}
