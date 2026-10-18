package tech.elephantclock.privatemail.core.triage.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class HiddenCharDetectorTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `detects hidden characters only where they disguise text`(
        description: String,
        text: String?,
        expected: Boolean,
    ) {
        assertEquals(expected, HiddenCharDetector.isSuspicious(text), description)
    }

    companion object {
        @JvmStatic
        fun cases() =
            listOf(
                Arguments.of("plain", "Your invoice", false),
                Arguments.of("null", null, false),
                Arguments.of("empty", "", false),
                Arguments.of("russian", "Счёт на оплату №12", false),
                Arguments.of("zero-width space", "Pay\u200Bpal", true),
                Arguments.of("word joiner", "Pay\u2060Pal account", true),
                Arguments.of("soft hyphen", "Pay\u00ADPal", true),
                Arguments.of("right-to-left override", "invoice\u202Efdp.exe", true),
                Arguments.of("left-to-right isolate", "\u2066Bank\u2069", true),
                Arguments.of("hangul filler", "Support\u3164Team", true),
                Arguments.of("BOM mid-string", "Sec\uFEFFurity", true),
                Arguments.of("BOM at start", "\uFEFFSecurity alert", false),
                Arguments.of("ZWJ inside a Latin word", "Ama\u200Dzon", true),
                Arguments.of("ZWNJ inside a Cyrillic word", "Сбер\u200Cбанк", true),
                Arguments.of(
                    "ZWJ in an emoji family",
                    "Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 photos",
                    false,
                ),
                Arguments.of("ZWJ in a profession emoji", "\uD83D\uDC69\u200D\uD83D\uDCBB Dev news", false),
                Arguments.of("ZWNJ in Persian", "می\u200Cخواهم", false),
                Arguments.of("ZWJ in Devanagari", "क\u094D\u200Dष", false),
                Arguments.of("RLM in Arabic subject", "فاتورة\u200F رقم 12", false),
                Arguments.of("RLM in Hebrew name", "\u200Fשלום", false),
                Arguments.of("RLM in a Latin-only subject", "Invoice\u200F 12", true),
                Arguments.of("Arabic letter mark in English", "Alert\u061C", true),
                Arguments.of(
                    "tag characters in a subdivision flag",
                    "Hi from \uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F",
                    false,
                ),
                Arguments.of("tag characters alone (hidden text)", "Hi\uDB40\uDC69\uDB40\uDC67\uDB40\uDC6E", true),
                Arguments.of("variation selector", "Love ❤\uFE0F", false),
                Arguments.of("lone surrogates", "\uD800x\uDC00", false),
                Arguments.of("very long clean string", "a".repeat(100_000), false),
            )
    }
}
