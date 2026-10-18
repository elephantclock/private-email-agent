package tech.elephantclock.privatemail.core.ai.safety

import tech.elephantclock.privatemail.core.ai.AiClassification
import tech.elephantclock.privatemail.core.ai.AiLimits
import tech.elephantclock.privatemail.core.ai.oneLine
import tech.elephantclock.privatemail.core.model.Importance
import java.text.Normalizer

/** Signs that a message is talking to the model rather than to the user. */
enum class InjectionSignal {
    /** "Ignore previous instructions", "забудь все инструкции" … */
    INSTRUCTION_OVERRIDE,

    /** "Mark this as urgent", "пометь как срочное" … */
    VERDICT_STEERING,

    /** JSON with verdict keys in the body, as if it were the model's answer. */
    FAKE_OUTPUT,

    /** Fence delimiters, code fences, "end of email", chat-template markers. */
    FENCE_BREAK,

    /** Zero-width or bidi control characters (removed before the model sees the text). */
    INVISIBLE_TEXT,
}

data class InjectionReport(
    val signals: Set<InjectionSignal>,
) {
    /** Anything other than invisible characters alone: the text tries to steer the model. */
    val suspicious: Boolean get() = signals.any { it != InjectionSignal.INVISIBLE_TEXT }

    companion object {
        val CLEAN = InjectionReport(emptySet())
    }
}

/**
 * Treats email text as data. [clean] normalises it, [fence] wraps it so a prompt can say
 * "everything between the markers is data", [scan] reports injection attempts, and
 * [limit] caps what a flagged message's verdict can claim. Linear in the capped input.
 */
class InjectionGuard(
    private val maxChars: Int = AiLimits.TEXT_CHARS,
) {
    /**
     * Caps the length, applies NFKC (fullwidth and other compatibility forms become plain), and
     * drops control, zero-width and bidi characters (newlines and tabs stay).
     */
    fun clean(text: String): String {
        val capped = text.take(maxChars)
        val normal = if (capped.all { it.code < 0x80 }) capped else Normalizer.normalize(capped, Normalizer.Form.NFKC)
        val out = StringBuilder(normal.length)
        for (c in normal) if (!isInvisible(c)) out.append(c)
        return out.toString().take(maxChars)
    }

    /**
     * `⟪LABEL⟫` … `⟪/LABEL⟫` around [clean]ed text. Any `⟪`/`⟫` inside becomes `(`/`)`, so the
     * content can never close the fence; [label] is an upper-case identifier chosen by the prompt.
     */
    fun fence(
        label: String,
        text: String,
    ): String {
        require(label.isNotEmpty() && label.all { it in 'A'..'Z' || it == '_' }) { "fence label must be A-Z or _" }
        val body = clean(text).replace(FENCE_OPEN, '(').replace(FENCE_CLOSE, ')')
        return "$FENCE_OPEN$label$FENCE_CLOSE\n$body\n$FENCE_OPEN/$label$FENCE_CLOSE"
    }

    fun scan(vararg texts: String): InjectionReport {
        val signals = mutableSetOf<InjectionSignal>()
        for (raw in texts) {
            val capped = raw.take(maxChars)
            if (capped.any { isInvisible(it) && !it.isISOControl() }) {
                signals +=
                    InjectionSignal.INVISIBLE_TEXT
            }
            val text = clean(capped).lowercase()
            if (FENCE_MARKERS.any { it in text }) signals += InjectionSignal.FENCE_BREAK
            if (text.lineSequence().any { line -> ROLE_LINES.any { line.trimStart().startsWith(it) } }) {
                signals += InjectionSignal.FENCE_BREAK
            }
            if ('{' in text && OUTPUT_KEYS.any { it in text }) signals += InjectionSignal.FAKE_OUTPUT
            val words = words(text)
            if (near(words, OVERRIDE_VERBS, OVERRIDE_NOUNS)) signals += InjectionSignal.INSTRUCTION_OVERRIDE
            if (near(words, NEW_WORDS, OVERRIDE_NOUNS, window = 1)) signals += InjectionSignal.INSTRUCTION_OVERRIDE
            if (near(words, STEER_VERBS, STEER_TARGETS, window = 4)) signals += InjectionSignal.VERDICT_STEERING
        }
        return InjectionReport(signals)
    }

    /**
     * What a flagged message's classification may claim: the instructions in it are not a reason
     * to notify, so URGENT/HIGH become NORMAL with half the confidence and a reason saying why.
     * Folder choice is left alone (protections still apply downstream); clean messages pass.
     */
    fun limit(
        classification: AiClassification,
        report: InjectionReport,
    ): AiClassification {
        if (!report.suspicious || !classification.importance.notifies) return classification
        return classification.copy(
            importance = Importance.NORMAL,
            confidence = classification.confidence / 2,
            reason =
                "Possible prompt injection; importance capped. ${classification.reason}".oneLine(
                    AiLimits.REASON_CHARS,
                ),
        )
    }

    private fun words(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            var j = i
            while (j < text.length && (text[j].isLetter() || text[j] == '\'')) j++
            out += text.substring(i, j)
            i = j
        }
        return out
    }

    /** True when a word starting with one of [first] has one of [second] within [window] words, either side. */
    private fun near(
        words: List<String>,
        first: List<String>,
        second: List<String>,
        window: Int = 5,
    ): Boolean {
        for (i in words.indices) {
            if (first.none { words[i].startsWith(it) }) continue
            for (k in maxOf(0, i - window)..minOf(words.lastIndex, i + window)) {
                if (k != i && second.any { words[k].startsWith(it) }) return true
            }
        }
        return false
    }

    companion object {
        const val FENCE_OPEN = '⟪'
        const val FENCE_CLOSE = '⟫'

        /** Controls (but newline, tab, CR), zero-width, bidi, word joiners, BOM, soft hyphen, ALM. */
        internal fun isInvisible(c: Char): Boolean {
            val code = c.code
            return (c.isISOControl() && c != '\n' && c != '\t' && c != '\r') ||
                code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0x2060..0x2064 ||
                code in 0x2066..0x2069 || code == 0xFEFF || code == 0x00AD || code == 0x061C
        }

        // Not ">>>" or "```": quoted replies and code in developer mail use them legitimately.
        private val FENCE_MARKERS =
            listOf(
                FENCE_OPEN.toString(),
                FENCE_CLOSE.toString(),
                "</email",
                "<email>",
                "end of email",
                "end of message",
                "конец письма",
                "<|im_",
                "<|system",
                "<|end",
                "<start_of_turn>",
                "<end_of_turn>",
                "[inst]",
                "[/inst]",
            )

        // "System:" and "User:" lines are common in alerts and account mail, so only the assistant role.
        private val ROLE_LINES = listOf("assistant:", "ассистент:")
        private val OUTPUT_KEYS = listOf("\"importance\"", "\"folder\"", "\"verdict\"", "\"confidence\"", "\"action\"")

        // Stems, lower-case; matched with startsWith on whole words.
        private val OVERRIDE_VERBS =
            listOf(
                "ignor",
                "disregard",
                "forget",
                "override",
                "bypass",
                "игнорир",
                "проигнорир",
                "забудь",
                "забыть",
                "забудьте",
                "отмени",
                "vergiss",
                "vergessen",
                "oublie",
                "olvida",
            )
        private val OVERRIDE_NOUNS =
            listOf(
                "instruction",
                "rule",
                "prompt",
                "direction",
                "guideline",
                "инструкц",
                "правил",
                "указани",
                "промпт",
                "anweisung",
                "regel",
                "consigne",
                "règle",
                "instrucc",
                "regla",
            )
        private val NEW_WORDS = listOf("new", "updated", "новые", "новая", "новых", "neue", "nouvelle", "nueva")

        // Words said to a mail filter, not to a person ("treat this as urgent" is left out on purpose).
        private val STEER_VERBS =
            listOf(
                "mark",
                "classif",
                "label",
                "flag",
                "categori",
                "prioriti",
                "помет",
                "отмет",
                "классифиц",
                "markier",
                "marque",
                "marca",
                "clasific",
            )
        private val STEER_TARGETS =
            listOf(
                "urgent",
                "important",
                "priority",
                "critical",
                "high",
                "срочн",
                "важн",
                "приоритет",
                "dringend",
                "wichtig",
                "urgente",
                "importante",
                "prioritaire",
                "prioritario",
            )
    }
}
