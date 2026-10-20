package tech.elephantclock.privatemail.ai

import tech.elephantclock.privatemail.core.ai.AiVerdictPolicy
import tech.elephantclock.privatemail.core.ai.ClassifyItem
import tech.elephantclock.privatemail.core.ai.FolderOption
import tech.elephantclock.privatemail.core.ai.queue.AiPriority
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.ai.toVerdictOrNull
import tech.elephantclock.privatemail.core.data.settings.DEFAULT_FOLDER_DESCRIPTIONS
import tech.elephantclock.privatemail.core.data.settings.DESCRIBED_FOLDERS
import tech.elephantclock.privatemail.core.data.triage.FolderGuide
import tech.elephantclock.privatemail.core.data.triage.TriageAi
import tech.elephantclock.privatemail.core.data.triage.TriageSettings
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.rules.Decision
import tech.elephantclock.privatemail.core.triage.rules.label

/**
 * Triage's [TriageAi] port over the app's [AiQueue] (which wraps `SafeAiEngine`, so the model
 * never sees unredacted text). Returns null, and so leaves the message rules-only, at once,
 * without queueing, when [canRun] is false: the app isn't the top foreground app (GenAI is
 * foreground-only, so every background drain stays rules-only) or the model isn't available.
 * The verdict goes back to the orchestrator, which applies `NeedsAi.accept` and the protections.
 */
class QueueTriageAi(
    private val queue: AiQueue,
    private val settings: TriageSettings,
    private val canRun: () -> Boolean,
    private val priority: AiPriority,
    private val policy: AiVerdictPolicy = AiVerdictPolicy(),
) : TriageAi {
    override suspend fun classify(
        message: MailItem,
        decision: Decision.NeedsAi,
    ): Verdict? {
        if (!canRun()) return null
        val folders = triageFolders(settings.forAccount(message.header.ref.accountId).folders)
        val item = ClassifyItem.from(message.header, message.body, decision.signals)
        // The queue caches per key: include the folder list, so new descriptions mean a new answer.
        val keyed = item.copy(key = "${item.key}|${fingerprint(folders)}")
        return queue.classify(keyed, folders, priority).toVerdictOrNull(policy)
    }

    private fun fingerprint(folders: List<FolderOption>): String =
        Integer.toHexString(folders.map { listOf(it.key, it.target, it.description) }.hashCode())
}

/** What Discarded and Junk mean, for the model; the user can't edit these (yet). */
internal val DISCARD_DESCRIPTION =
    "Marketing, newsletters, promotions, cold sales pitches and social-network notifications " +
        "nobody needs to read. Never real conversations with people."
internal const val JUNK_DESCRIPTION = "Spam, scams and phishing."

/**
 * The folders the model may choose, from the account's [FolderGuide]s: Priority (always), the
 * value and custom folders that are switched on, then Discarded and Junk (an AI discard or junk
 * under 0.8 confidence is kept, and protected senders never leave Priority). Keys are the role
 * name, or `custom-N` for custom folders. No guides (settings not from the store): the defaults.
 */
fun triageFolders(guides: List<FolderGuide>): List<FolderOption> {
    val described =
        guides.ifEmpty {
            DESCRIBED_FOLDERS.map { FolderGuide(FolderTarget(it), DEFAULT_FOLDER_DESCRIPTIONS.getValue(it)) }
        }
    var custom = 0
    val offered =
        described
            .filter { it.enabled || it.target.role == FolderRole.PRIORITY }
            .filter { it.target.role != FolderRole.DISCARDED && it.target.role != FolderRole.JUNK }
            .distinctBy { it.target }
            .map { g ->
                val key =
                    if (g.target.role ==
                        FolderRole.CUSTOM
                    ) {
                        "custom-${++custom}"
                    } else {
                        g.target.role.name
                            .lowercase()
                    }
                FolderOption(key, g.target, g.target.label(), g.description)
            }
    val priority =
        if (offered.none { it.target.role == FolderRole.PRIORITY }) {
            listOf(
                FolderOption(
                    "priority",
                    FolderTarget.Priority,
                    FolderTarget.Priority.label(),
                    DEFAULT_FOLDER_DESCRIPTIONS.getValue(FolderRole.PRIORITY),
                ),
            )
        } else {
            emptyList()
        }
    return priority + offered +
        FolderOption("discarded", FolderTarget.Discarded, FolderTarget.Discarded.label(), DISCARD_DESCRIPTION) +
        FolderOption("junk", FolderTarget.Junk, FolderTarget.Junk.label(), JUNK_DESCRIPTION)
}
