package tech.elephantclock.privatemail.core.ai.mlkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.oneLine
import tech.elephantclock.privatemail.core.ai.safety.InjectionGuard
import tech.elephantclock.privatemail.core.ai.safety.InjectionSignal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Where a thread stands, as the Prompt API reports it. The app maps [key] to its own words. */
enum class BriefStatus(
    val key: String,
) {
    WAITING_ON_YOU("waiting_on_you"),
    WAITING_ON_THEM("waiting_on_them"),
    RESOLVED("resolved"),
    INFO("info"),
    ;

    companion object {
        fun of(key: String?): BriefStatus? = entries.firstOrNull { it.key == key }
    }
}

/** The Prompt API's part of the brief, validated. */
data class BriefFields(
    val gist: String?,
    val status: BriefStatus,
    /** ISO date (YYYY-MM-DD) or null. */
    val deadline: String?,
    val nextSteps: List<String>,
)

/**
 * Strict reader for the Prompt API's JSON answer. Accepted: exactly one JSON object, optionally
 * inside a single ``` / ```json code fence, nothing else around it. Rejected as
 * [AiFailure.InvalidOutput]: any other text, unknown or missing keys, wrong types, an unknown
 * status, a deadline that isn't a real YYYY-MM-DD date, more than [MAX_STEPS] steps, and output
 * carrying fence or chat-template markers (a sign the email steered the model). Strings are made
 * one line and capped. A step with a link, an e-mail address or a long digit run is dropped: next
 * steps come from untrusted mail, and the app never turns them into links.
 */
object BriefParser {
    const val MAX_STEPS = 3
    const val GIST_CHARS = 420
    const val STEP_CHARS = 140
    private const val MAX_RAW_CHARS = 4_000

    private val json = Json { isLenient = false }
    private val guard = InjectionGuard()

    fun parse(
        raw: String,
        withGist: Boolean,
    ): AiResult<BriefFields> {
        if (raw.length > MAX_RAW_CHARS) return invalid("answer too long")
        val text = unfence(raw.trim()) ?: return invalid("text around the JSON")
        if (!text.startsWith("{") || !text.endsWith("}")) return invalid("not a JSON object")
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                return invalid("malformed JSON")
            }
        val obj = element as? JsonObject ?: return invalid("not a JSON object")
        val expected = if (withGist) KEYS + "gist" else KEYS
        if (obj.keys != expected) return invalid("keys ${obj.keys.sorted()}")
        val signals = guard.scan(text).signals
        if (InjectionSignal.FENCE_BREAK in signals) return invalid("fence or role markers in the answer")

        val gist =
            if (withGist) {
                (string(obj["gist"]) ?: return invalid("gist")).oneLine(GIST_CHARS).ifBlank {
                    return invalid("empty gist")
                }
            } else {
                null
            }
        val status = BriefStatus.of(string(obj["status"])) ?: return invalid("status")
        val deadline =
            when (val d = obj["deadline"]) {
                is JsonNull -> null
                else -> isoDate(string(d) ?: return invalid("deadline")) ?: return invalid("deadline not a date")
            }
        val steps = obj["next_steps"] as? JsonArray ?: return invalid("next_steps")
        if (steps.size > MAX_STEPS) return invalid("too many next steps")
        val nextSteps =
            steps
                .map { (string(it) ?: return invalid("next step")).oneLine(STEP_CHARS) }
                .filter { it.isNotBlank() && safeStep(it) }
                .distinct()
        return AiResult.Ok(BriefFields(gist, status, deadline, nextSteps))
    }

    /** The body of a single code fence, the text itself when unfenced, or null for anything else. */
    internal fun unfence(text: String): String? {
        if (!text.startsWith("```")) return text.takeIf { "```" !in it }
        val firstLine = text.substringBefore('\n')
        val lang = firstLine.removePrefix("```").trim()
        if (lang.isNotEmpty() && !lang.equals("json", ignoreCase = true)) return null
        if (!text.endsWith("```") || text.length < firstLine.length + 3) return null
        val inner = text.substring(firstLine.length, text.length - 3).trim()
        return inner.takeIf { "```" !in it }
    }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isoDate(s: String): String? {
        if (s.length != 10) return null
        return try {
            LocalDate.parse(s).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    internal fun safeStep(step: String): Boolean {
        val lower = step.lowercase()
        if (LINK_MARKERS.any { it in lower }) return false
        if ('@' in step) return false
        var run = 0
        for (c in step) {
            run = if (c.isDigit()) run + 1 else 0
            if (run >= 6) return false
        }
        return true
    }

    private fun invalid(detail: String) = AiResult.Failed(AiFailure.InvalidOutput("brief: $detail"))

    private val KEYS = setOf("status", "deadline", "next_steps")
    private val LINK_MARKERS = listOf("http:", "https:", "www.", "://", ".com/", ".ru/", "bit.ly", "t.me/")
}
