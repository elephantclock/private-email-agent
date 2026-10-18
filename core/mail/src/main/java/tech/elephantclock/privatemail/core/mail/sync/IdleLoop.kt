package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.FolderClosedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.stopRetrying
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.cancellable
import kotlin.random.Random

/** What an [IdleLoop] reports. The collector syncs on [Connected] and [Changed]. */
sealed interface IdleEvent {
    /** Signed in and watching the folder. Anything may have changed while we were away: sync now. */
    data object Connected : IdleEvent

    /** The server reported a change (new mail, flags, expunge), or the IDLE was renewed. */
    data object Changed : IdleEvent

    /** The connection failed or dropped; the loop reconnects after [retryInMillis]. */
    data class Disconnected(
        val error: AuthError?,
        val cause: Throwable?,
        val retryInMillis: Long,
    ) : IdleEvent

    /** A problem retrying can't fix soon (see [AuthError.stopRetrying]). The flow completes after this. */
    data class Stopped(
        val error: AuthError,
    ) : IdleEvent
}

/** Exponential backoff with jitter: attempt 0 waits about [initialMillis], doubling up to [maxMillis]. */
class Backoff(
    private val initialMillis: Long = 1_000,
    private val maxMillis: Long = 5 * 60_000,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val base = (initialMillis shl attempt.coerceIn(0, 30)).coerceIn(initialMillis, maxMillis)
        // Between half and the full delay, so many clients don't reconnect in step.
        return base / 2 + random.nextLong(base / 2 + 1)
    }
}

/**
 * Watches one folder with IMAP IDLE (RFC 2177), read-only, on its own connection. Runs while
 * the flow is collected; the app collects it only while it is in the foreground.
 *
 * - IDLE is renewed every [renewMillis] (well under the 29 minutes servers allow, and short
 *   enough to keep mobile NAT mappings alive): the renewal ends IDLE with DONE and a NOOP,
 *   which also notices a dead connection (Angus ignores read timeouts *while* idling, but
 *   not while waiting for DONE's reply).
 * - On a dropped connection it reconnects with [backoff]; the attempt count resets once a
 *   session has stayed up for [stableMillis], so a server that accepts and then drops at once
 *   can't cause a reconnect storm.
 * - Errors with [AuthError.stopRetrying] (wrong password, untrusted certificate, locked
 *   account) end the flow with [IdleEvent.Stopped]: repeated sign-in attempts can get an
 *   account locked.
 * - Servers without IDLE: one sign-in to learn that, then [IdleEvent.Changed] every [pollMillis].
 * - Cancelling the collector drops the connection at once ([ImapConnection.abort]), so a
 *   blocking read never keeps a thread waiting for the server.
 */
class IdleLoop(
    private val connect: () -> MailResult<ImapConnection>,
    private val folderPath: String = "INBOX",
    private val renewMillis: Long = 4 * 60_000,
    private val pollMillis: Long = 5 * 60_000,
    private val stableMillis: Long = 60_000,
    private val minCycleMillis: Long = 1_000,
    private val backoff: Backoff = Backoff(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun events(): Flow<IdleEvent> = channelFlow { run() }

    private suspend fun ProducerScope<IdleEvent>.run() {
        var attempt = 0
        while (true) {
            // Not cancellable mid-call (a socket connect can't be interrupted; it has its own
            // timeouts), so a connection that arrives after cancellation is still closed below.
            val result = withContext(io + NonCancellable) { connect() }
            var error: AuthError? = null
            var cause: Throwable? = null
            when (result) {
                is MailResult.Failed -> {
                    if (result.error.stopRetrying) {
                        send(IdleEvent.Stopped(result.error))
                        return
                    }
                    error = result.error
                }

                is MailResult.Ok -> {
                    val connection = result.value
                    val startedAt = clock()
                    try {
                        currentCoroutineContext().ensureActive()
                        if (connection.capabilities.idle) {
                            session(connection)
                        } else {
                            connection.abort()
                            poll()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        cause = e
                    } finally {
                        withContext(io + NonCancellable) { connection.abort() }
                    }
                    if (clock() - startedAt >= stableMillis) attempt = 0
                }
            }
            val wait = backoff.delayFor(attempt++)
            send(IdleEvent.Disconnected(error, cause, wait))
            delay(wait)
        }
    }

    private suspend fun ProducerScope<IdleEvent>.poll(): Nothing {
        send(IdleEvent.Connected)
        while (true) {
            delay(pollMillis)
            send(IdleEvent.Changed)
        }
    }

    private suspend fun ProducerScope<IdleEvent>.session(connection: ImapConnection) {
        val folder = connection.cancellable(io) { it.openReadOnly(folderPath) }
        send(IdleEvent.Connected)
        while (true) {
            val cycleStart = clock()
            coroutineScope {
                val renew =
                    launch {
                        delay(renewMillis)
                        // A command from another thread makes Angus end IDLE (DONE) first; then NOOP.
                        withContext(io) { runCatching { folder.doCommand { it.noop() } } }
                    }
                connection.cancellable(io) { folder.idle(true) }
                renew.cancel()
            }
            if (!folder.isOpen) throw FolderClosedException(folder, "connection lost while idling")
            send(IdleEvent.Changed)
            // A server that answers IDLE at once, over and over, must not become a busy loop.
            val elapsed = clock() - cycleStart
            if (elapsed < minCycleMillis) delay(minCycleMillis - elapsed)
        }
    }
}
