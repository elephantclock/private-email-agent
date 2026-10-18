package tech.elephantclock.privatemail.core.mail.imap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs blocking IMAP work on [dispatcher]. Socket reads ignore coroutine cancellation, so on
 * cancellation this drops the connection ([ImapConnection.abort]), which makes the blocked
 * call fail at once instead of waiting for the server or the read timeout.
 */
suspend fun <T> ImapConnection.cancellable(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: (ImapConnection) -> T,
): T =
    coroutineScope {
        val work = async(dispatcher) { block(this@cancellable) }
        try {
            work.await()
        } catch (e: CancellationException) {
            abort()
            throw e
        }
    }
