package tech.elephantclock.privatemail.core.ai.safety

import tech.elephantclock.privatemail.core.ai.AiLimits

/** What a placeholder hides. */
enum class SecretKind {
    /** API keys and long tokens, incl. `token=` / `api_key=` values. */
    SECRET,
    JWT,

    /** Card numbers (Luhn-checked). */
    CARD,
    IBAN,

    /** One-time / verification codes and PINs (digits near a code keyword). */
    CODE,

    /** The value after "password:", "пароль:" and the like. */
    PASSWORD,
}

/**
 * Masks secrets before any text reaches the model: `⟦CARD_1⟧`, `⟦IBAN_2⟧` … Start one
 * [session] per AI call (or per thread) so the same value always gets the same placeholder and
 * generated text can be [RedactionSession.restore]d.
 *
 * Input is capped to [maxChars] first; every scanner is linear. The placeholder brackets
 * `⟦ ⟧` already in the input are turned into `[ ]`, so an attacker can't write a placeholder
 * that a later restore would fill with a real secret.
 */
class Redactor(
    private val maxChars: Int = AiLimits.TEXT_CHARS,
) {
    fun session(): RedactionSession = RedactionSession(maxChars)

    /** One-off redaction when nothing needs restoring. */
    fun redact(text: String): String = session().redact(text)

    companion object {
        const val OPEN = '⟦'
        const val CLOSE = '⟧'

        /** Kinds a reply draft may get back: an IBAN quoted in a reply is the user's call. */
        val REPLY_RESTORABLE: Set<SecretKind> = setOf(SecretKind.IBAN)
    }
}

class RedactionSession internal constructor(
    private val maxChars: Int,
) {
    private val byValue = HashMap<String, String>()
    private val byPlaceholder = HashMap<String, Pair<SecretKind, String>>()
    private val counters = IntArray(SecretKind.entries.size)

    /** How many distinct secrets were masked so far, per kind. */
    fun count(kind: SecretKind): Int = counters[kind.ordinal]

    val isEmpty: Boolean get() = byPlaceholder.isEmpty()

    fun redact(text: String): String {
        val input = neutralise(text.take(maxChars))
        val spans = SecretScanners.scan(input)
        if (spans.isEmpty()) return input
        val out = StringBuilder(input.length)
        var at = 0
        for (span in spans) {
            out.append(input, at, span.start)
            out.append(placeholder(span.kind, input.substring(span.start, span.end)))
            at = span.end
        }
        out.append(input, at, input.length)
        return out.toString()
    }

    /**
     * Puts the original values back for placeholders of [kinds] (all by default). Unknown or
     * other-kind placeholders stay as they are, so a hallucinated `⟦CARD_9⟧` is visible.
     */
    fun restore(
        text: String,
        kinds: Set<SecretKind> = SecretKind.entries.toSet(),
    ): String {
        if (byPlaceholder.isEmpty() || Redactor.OPEN !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == Redactor.OPEN) {
                // Placeholders are short: look at most MAX_PLACEHOLDER chars ahead (linear).
                var j = i + 1
                while (j < text.length && j - i <= MAX_PLACEHOLDER && text[j] != Redactor.CLOSE &&
                    text[j] != Redactor.OPEN
                ) {
                    j++
                }
                if (j < text.length && text[j] == Redactor.CLOSE) {
                    val found = byPlaceholder[text.substring(i, j + 1)]
                    if (found != null && found.first in kinds) {
                        out.append(found.second)
                        i = j + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun placeholder(
        kind: SecretKind,
        value: String,
    ): String =
        byValue.getOrPut(canonical(kind, value)) {
            val n = ++counters[kind.ordinal]
            "${Redactor.OPEN}${kind.name}_$n${Redactor.CLOSE}".also { byPlaceholder[it] = kind to value }
        }

    /** "4111 1111 1111 1111" and "4111111111111111" are the same card: one placeholder. */
    private fun canonical(
        kind: SecretKind,
        value: String,
    ): String =
        when (kind) {
            SecretKind.CARD, SecretKind.IBAN, SecretKind.CODE -> kind.name + ":" + value.filter { it.isLetterOrDigit() }
            else -> kind.name + ":" + value
        }

    private fun neutralise(text: String): String =
        if (Redactor.OPEN !in text && Redactor.CLOSE !in text) {
            text
        } else {
            text.replace(Redactor.OPEN, '[').replace(Redactor.CLOSE, ']')
        }

    private companion object {
        const val MAX_PLACEHOLDER = 20
    }
}
