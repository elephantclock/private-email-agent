package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.imap.IMAPFolder
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.UidFolder

/** APPEND that reports where the message landed. */
internal object Appender {
    fun append(
        connection: ImapConnection,
        path: String,
        mime: MimeMessage,
        messageId: String,
        capabilities: Capabilities,
    ): StoredMessage {
        val folder = connection.imapStore.getFolder(path) as IMAPFolder
        // STATUS before the APPEND: the new copy's UID is at least this.
        val validityBefore = folder.uidValidity
        val uidNextBefore = folder.uidNext
        val appended = folder.appendUIDMessages(arrayOf(mime)).firstOrNull()
        if (capabilities.uidPlus && appended != null && appended.uid > 0) {
            return StoredMessage(path, appended.uidvalidity, appended.uid, messageId)
        }
        // No APPENDUID: find it by Message-ID among UIDs given out since. Same-id drafts are older, so take the newest.
        val uid =
            UidFolder.withReadOnly(connection, path, capabilities) { f ->
                if (f.uidValidity != validityBefore) return@withReadOnly null
                f.searchMessageId(messageId, minUid = uidNextBefore.coerceAtLeast(1)).maxOrNull()
            }
        return StoredMessage(path, validityBefore, uid, messageId)
    }
}
