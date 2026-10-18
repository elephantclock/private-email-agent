package tech.elephantclock.privatemail.core.ai

import tech.elephantclock.privatemail.core.model.Address
import kotlin.jvm.JvmInline

/** A BCP-47 language tag such as "en", "ru" or "pt-BR"; [UNDETERMINED] when unknown. */
@JvmInline
value class LanguageTag(
    val value: String,
) {
    init {
        require(isValid(value)) { "not a BCP-47 tag: '$value'" }
    }

    val primary: String get() = value.substringBefore('-').lowercase()

    override fun toString() = value

    companion object {
        val UNDETERMINED = LanguageTag("und")
        val ENGLISH = LanguageTag("en")
        val RUSSIAN = LanguageTag("ru")

        /** Letters for the primary subtag (2–8), then alphanumeric subtags (1–8), dash-separated. */
        fun isValid(tag: String): Boolean {
            if (tag.isEmpty() || tag.length > 35) return false
            val parts = tag.split('-')
            val first = parts.first()
            if (first.length !in 2..8 || !first.all { it in 'a'..'z' || it in 'A'..'Z' }) return false
            return parts.drop(1).all { p -> p.length in 1..8 && p.all { it.isAsciiLetterOrDigit() } }
        }

        private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}

/** One message of a thread, oldest first in [AiThread.messages]. Quoted history already removed. */
data class ThreadMessage(
    val from: Address?,
    val sentAtMillis: Long?,
    val text: String,
    /** True for mail the user wrote (from the Sent folder). */
    val fromUser: Boolean = false,
)

data class AiThread(
    val subject: String,
    val messages: List<ThreadMessage>,
) {
    init {
        require(messages.isNotEmpty()) { "a thread has at least one message" }
    }
}

/** The thread brief: gist from the Summarization API, the rest from the Prompt API (T32). */
data class ThreadSummary(
    val gist: String,
    val status: String? = null,
    val deadline: String? = null,
    val nextSteps: List<String> = emptyList(),
)

enum class ReplyTone { NEUTRAL, FORMAL, FRIENDLY, BRIEF }

/** A suggested reply. Always shown in the editor; nothing is ever sent without the user's tap. */
data class ReplyDraft(
    val text: String,
    val language: LanguageTag,
)

enum class RewriteStyle { SHORTER, MORE_FORMAL, FRIENDLIER, REPHRASE, ELABORATE }

/** Rewritten or proofread text: the best candidate plus any others the model offered. */
data class TextSuggestion(
    val text: String,
    val alternatives: List<String> = emptyList(),
)

data class DetectedLanguage(
    val language: LanguageTag,
    /** 0..1. */
    val confidence: Float,
)

data class Translation(
    val text: String,
    val from: LanguageTag,
    val to: LanguageTag,
)
