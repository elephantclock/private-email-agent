package tech.elephantclock.privatemail.core.ai.mlkit

import tech.elephantclock.privatemail.core.ai.AiClassification
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.FolderOption
import tech.elephantclock.privatemail.core.model.Importance

/**
 * Turns the model's answer into an [AiClassification], or [AiFailure.InvalidOutput].
 *
 * Accepted: exactly one JSON object with exactly the keys `folder` (one of the offered keys,
 * case-sensitive), `importance` (`urgent`, `high`, `normal` or `low`, lower case), `confidence`
 * (a number from 0 to 1), `reason` and `summary` (strings). Tolerated around it: whitespace, and
 * one Markdown code fence (```` ``` ```` or ```` ```json ````). Everything else is rejected: prose
 * before or after, two objects, unknown or missing keys, duplicate keys, wrong types, a folder
 * that wasn't offered, an empty answer. A model that echoes an injected instruction either
 * breaks the shape (rejected here) or picks a valid answer, whose importance `SafeAiEngine` caps.
 */
class VerdictParser {
    fun parse(
        raw: String?,
        folders: List<FolderOption>,
    ): AiResult<AiClassification> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return invalid("empty answer")
        if (text.length > MAX_ANSWER_CHARS) return invalid("answer too long")
        val json = unfence(text) ?: return invalid("not a single JSON object")
        val obj =
            try {
                FlatJson.parseObject(json)
            } catch (e: FlatJsonException) {
                return invalid("bad JSON: ${e.message}")
            }
        if (obj.keys != KEYS) return invalid("keys must be exactly $KEYS, were ${obj.keys.take(MAX_KEYS_SHOWN)}")
        val folderKey = (obj["folder"] as? FlatValue.Str)?.value ?: return invalid("folder is not a string")
        val folder = folders.firstOrNull { it.key == folderKey } ?: return invalid("folder not offered")
        val importanceName =
            (obj["importance"] as? FlatValue.Str)?.value ?: return invalid("importance is not a string")
        val importance = IMPORTANCE[importanceName] ?: return invalid("unknown importance")
        val confidence = (obj["confidence"] as? FlatValue.Num)?.value ?: return invalid("confidence is not a number")
        if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) return invalid("confidence outside 0..1")
        val reason = (obj["reason"] as? FlatValue.Str)?.value ?: return invalid("reason is not a string")
        val summary = (obj["summary"] as? FlatValue.Str)?.value ?: return invalid("summary is not a string")
        return AiResult.Ok(
            AiClassification(
                target = folder.target,
                importance = importance,
                confidence = confidence.toFloat(),
                reason = reason,
                summary = summary.ifBlank { null },
            ),
        )
    }

    /** The text inside one optional code fence, or the text itself; null for stray fences. */
    private fun unfence(text: String): String? {
        if (!text.startsWith(FENCE)) return text.takeUnless { FENCE in it }
        val firstLineEnd = text.indexOf('\n')
        if (firstLineEnd < 0 || !text.endsWith(FENCE) || text.length < 2 * FENCE.length) return null
        val language = text.substring(FENCE.length, firstLineEnd).trim()
        if (language.isNotEmpty() && language != "json") return null
        val inner = text.substring(firstLineEnd + 1, text.length - FENCE.length)
        return inner.takeUnless { FENCE in it }
    }

    private fun invalid(detail: String) = AiResult.Failed(AiFailure.InvalidOutput(detail))

    private companion object {
        const val FENCE = "```"
        const val MAX_ANSWER_CHARS = 2_000
        const val MAX_KEYS_SHOWN = 8
        val KEYS = setOf("folder", "importance", "confidence", "reason", "summary")
        val IMPORTANCE =
            mapOf(
                "urgent" to Importance.URGENT,
                "high" to Importance.HIGH,
                "normal" to Importance.NORMAL,
                "low" to Importance.LOW,
            )
    }
}
