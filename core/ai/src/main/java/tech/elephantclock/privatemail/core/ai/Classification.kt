package tech.elephantclock.privatemail.core.ai

import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource

/** Input caps, so a hostile message can't blow the model's context or our scanners' budget. */
object AiLimits {
    const val SUBJECT_CHARS = 300
    const val NAME_CHARS = 100
    const val PREVIEW_CHARS = 4_000
    const val TEXT_CHARS = 8_000
    const val REASON_CHARS = 200
    const val SUMMARY_CHARS = 200
    const val FOLDER_DESCRIPTION_CHARS = 300
}

/**
 * One folder the model may choose, from the user's folder list. The model answers with [key]
 * only; anything outside the offered keys is [AiFailure.InvalidOutput].
 */
data class FolderOption(
    /** Short stable id used in the prompt and the model's answer, e.g. "money" or "custom-3". */
    val key: String,
    val target: FolderTarget,
    val displayName: String,
    /** The user's plain-language description ("Invoices, payments, receipts"). */
    val description: String,
) {
    init {
        require(key.isNotBlank() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "folder key must be a short identifier, was '$key'"
        }
    }
}

/** The email fields the model sees. Text is capped here; redaction and fencing happen in `safety`. */
data class ClassifyItem(
    /** Cache and dedupe key: the Message-ID, or the server ref when there is none. */
    val key: String,
    val from: Address?,
    val subject: String,
    val preview: String,
    val receivedAtMillis: Long,
    val to: List<Address> = emptyList(),
    /** What the rules already found; lets the prompt say "bulk mail" or "a reply to you". */
    val signals: Signals? = null,
) {
    companion object {
        fun from(
            header: MessageHeader,
            body: MessageBody?,
            signals: Signals? = null,
        ) = ClassifyItem(
            key = keyOf(header),
            from = header.from?.let { it.copy(name = it.name?.take(AiLimits.NAME_CHARS)) },
            subject = header.subject.take(AiLimits.SUBJECT_CHARS),
            preview = body?.textPreview.orEmpty().take(AiLimits.PREVIEW_CHARS),
            receivedAtMillis = header.receivedAtMillis,
            to = header.to.take(MAX_RECIPIENTS),
            signals = signals,
        )

        /** Message-ID when present (stable across folders and moves), else the server location. */
        fun keyOf(header: MessageHeader): String =
            header.messageId?.takeIf { it.isNotBlank() }
                ?: header.ref.let { "ref:${it.accountId}/${it.folderPath}/${it.uidValidity}/${it.uid}" }

        private const val MAX_RECIPIENTS = 10
    }
}

data class ClassifyRequest(
    val folders: List<FolderOption>,
    val items: List<ClassifyItem>,
) {
    init {
        require(folders.isNotEmpty()) { "offer at least one folder" }
        require(folders.map { it.key }.toSet().size == folders.size) { "folder keys must be unique" }
    }

    fun folder(key: String): FolderOption? = folders.firstOrNull { it.key == key }
}

/** What the model said about one message, after parsing and validation (folder is one offered). */
data class AiClassification(
    val target: FolderTarget,
    val importance: Importance,
    /** As reported by the model; [toVerdict] clamps it to 0..1. */
    val confidence: Float,
    val reason: String,
    val summary: String? = null,
)

/** One message's result inside a batch; a batch can partly succeed. */
data class ClassifyOutcome(
    val key: String,
    val result: AiResult<AiClassification>,
)

/** How cautious to be with what the model says. */
data class AiVerdictPolicy(
    /** An AI discard or junk below this confidence is kept instead (zero false discards). */
    val minDiscardConfidence: Float = 0.8f,
)

/**
 * Maps the model's answer onto a [Verdict] through the factory functions, so the invariants
 * hold by construction: source is always [VerdictSource.AI]; discard and junk always carry LOW
 * importance; an unsure discard becomes KEEP NORMAL. The caller still applies the message's
 * protections (`Decision.NeedsAi.accept`).
 */
fun AiClassification.toVerdict(policy: AiVerdictPolicy = AiVerdictPolicy()): Verdict {
    val conf = if (confidence.isNaN()) 0f else confidence.coerceIn(0f, 1f)
    val why =
        reason
            .oneLine(
                AiLimits.REASON_CHARS,
            ).ifBlank { "AI: ${target.customName ?: target.role.name.lowercase()}" }
    val verdict =
        when (target.role) {
            FolderRole.PRIORITY -> {
                Verdict.keep(importance, VerdictSource.AI, why, conf)
            }

            FolderRole.DISCARDED, FolderRole.JUNK -> {
                if (conf < policy.minDiscardConfidence) {
                    Verdict.keep(Importance.NORMAL, VerdictSource.AI, "Kept: AI unsure (${pct(conf)}): $why", conf)
                } else if (target.role == FolderRole.JUNK) {
                    Verdict.junk(VerdictSource.AI, why, conf)
                } else {
                    Verdict.discard(VerdictSource.AI, why, conf)
                }
            }

            else -> {
                Verdict.file(target, importance, VerdictSource.AI, why, conf)
            }
        }
    return verdict.copy(summary = summary?.oneLine(AiLimits.SUMMARY_CHARS)?.ifBlank { null })
}

/** The one-liner the orchestrator needs: a verdict, or null to fall back to the rules. */
fun AiResult<AiClassification>.toVerdictOrNull(policy: AiVerdictPolicy = AiVerdictPolicy()): Verdict? =
    getOrNull()?.toVerdict(policy)

/** Classifies a single message; a missing outcome counts as invalid output. */
suspend fun AiEngine.classifyOne(
    folders: List<FolderOption>,
    item: ClassifyItem,
): AiResult<AiClassification> =
    classify(ClassifyRequest(folders, listOf(item))).firstOrNull { it.key == item.key }?.result
        ?: AiResult.Failed(AiFailure.InvalidOutput("no result for ${item.key}"))

private fun pct(conf: Float) = "${(conf * 100).toInt()}%"

/** Collapses whitespace runs (incl. newlines) to one space and caps the length. */
internal fun String.oneLine(max: Int): String {
    val out = StringBuilder(minOf(length, max))
    var space = false
    for (c in this) {
        if (out.length >= max) break
        if (c.isWhitespace() || c.isISOControl()) {
            space = out.isNotEmpty()
        } else {
            if (space) out.append(' ')
            space = false
            if (out.length < max) out.append(c)
        }
    }
    return out.toString()
}
