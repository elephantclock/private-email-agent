package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class SerializationTest {
    private val json = Json { encodeDefaults = false }

    private fun <T> roundTrip(
        value: T,
        serializer: KSerializer<T>,
    ) {
        val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, value))
        assertEquals(value, decoded)
        assertEquals(value.hashCode(), decoded.hashCode())
    }

    @TestFactory
    fun `every model type survives a JSON round trip`(): List<DynamicTest> =
        listOf(
            "Account" to { roundTrip(Fixtures.account, serializer()) },
            "MailFolder" to { roundTrip(Fixtures.folder, serializer()) },
            "MessageHeader" to { roundTrip(Fixtures.header, serializer()) },
            "MessageBody" to { roundTrip(Fixtures.body, serializer()) },
            "Signals" to { roundTrip(Fixtures.signals, serializer()) },
            "Verdict" to { roundTrip(Fixtures.verdict, serializer()) },
            "TriageRule (domain)" to { roundTrip(Fixtures.rule, serializer()) },
            "TriageRule (sender, VIP)" to {
                roundTrip(
                    Fixtures.rule.copy(
                        matcher = RuleMatcher.Sender("us@informap.ae"),
                        action = VerdictAction.KEEP,
                        target = FolderTarget.Priority,
                        vip = true,
                    ),
                    serializer(),
                )
            },
            "custom FolderTarget" to { roundTrip(FolderTarget.custom("Partners"), serializer()) },
            "Importance" to { Importance.entries.forEach { roundTrip(it, serializer()) } },
            "FolderRole" to { FolderRole.entries.forEach { roundTrip(it, serializer()) } },
        ).map { (name, test) -> DynamicTest.dynamicTest(name, test) }

    @Test
    fun `value classes and sealed matchers have stable JSON`() {
        assertEquals("\"acc-1\"", json.encodeToString(Fixtures.accountId))
        assertEquals(
            """{"type":"domain","domain":"appfigures.com"}""",
            json.encodeToString<RuleMatcher>(RuleMatcher.Domain("appfigures.com")),
        )
    }

    @Test
    fun `decoding runs the same validation as the constructor`() {
        val bad =
            json
                .encodeToString(Fixtures.verdict)
                .replace("\"action\":\"FILE\"", "\"action\":\"DISCARD\"")
        assertTrue("DISCARD" in bad)
        assertThrows<IllegalArgumentException> { json.decodeFromString<Verdict>(bad) }
    }

    @Test
    fun `unknown enum values are rejected`() {
        assertThrows<SerializationException> { json.decodeFromString<Importance>("\"CRITICAL\"") }
    }
}
