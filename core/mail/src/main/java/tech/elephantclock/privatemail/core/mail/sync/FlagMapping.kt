package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.Flags
import tech.elephantclock.privatemail.core.model.MessageFlag

private val FLAG_MAP =
    mapOf(
        Flags.Flag.SEEN to MessageFlag.SEEN,
        Flags.Flag.ANSWERED to MessageFlag.ANSWERED,
        Flags.Flag.FLAGGED to MessageFlag.FLAGGED,
        Flags.Flag.DRAFT to MessageFlag.DRAFT,
        Flags.Flag.DELETED to MessageFlag.DELETED,
    )

/** The system flags we track; keywords and \Recent are ignored. */
internal fun Flags.toModel(): Set<MessageFlag> {
    val flags = this
    return FLAG_MAP.filterKeys { flags.contains(it) }.values.toSet()
}
