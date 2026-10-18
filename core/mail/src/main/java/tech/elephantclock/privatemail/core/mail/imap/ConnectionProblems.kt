package tech.elephantclock.privatemail.core.mail.imap

import jakarta.mail.FolderClosedException
import jakarta.mail.StoreClosedException
import java.io.IOException

/**
 * A dropped, reset or timed-out connection somewhere in the cause chain, as opposed to a
 * problem with one folder or message. Worth retrying later; the others usually aren't.
 */
fun Throwable.isConnectionProblem(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is IOException || it is FolderClosedException || it is StoreClosedException
    }
