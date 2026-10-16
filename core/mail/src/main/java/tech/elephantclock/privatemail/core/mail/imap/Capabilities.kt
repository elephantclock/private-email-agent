package tech.elephantclock.privatemail.core.mail.imap

/** IMAP extensions the sync and move engines care about. */
data class Capabilities(
    val idle: Boolean,
    val move: Boolean,
    val uidPlus: Boolean,
    val condStore: Boolean,
    val specialUse: Boolean,
    val gmail: Boolean,
) {
    companion object {
        private const val IDLE = "IDLE"
        private const val MOVE = "MOVE"
        private const val UIDPLUS = "UIDPLUS"
        private const val CONDSTORE = "CONDSTORE"
        private const val SPECIAL_USE = "SPECIAL-USE"
        private const val GMAIL = "X-GM-EXT-1"

        /** Every atom we ask the server about. */
        val KNOWN = listOf(IDLE, MOVE, UIDPLUS, CONDSTORE, SPECIAL_USE, GMAIL)

        fun from(has: (String) -> Boolean) =
            Capabilities(
                idle = has(IDLE),
                move = has(MOVE),
                uidPlus = has(UIDPLUS),
                condStore = has(CONDSTORE),
                specialUse = has(SPECIAL_USE),
                gmail = has(GMAIL),
            )

        /** From a raw `CAPABILITY` response line, e.g. "IMAP4rev1 IDLE MOVE". */
        fun parse(line: String): Capabilities {
            val atoms =
                line
                    .trim()
                    .split(Regex("\\s+"))
                    .map { it.uppercase() }
                    .toSet()
            return from { it in atoms }
        }
    }
}
