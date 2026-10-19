package tech.elephantclock.privatemail.core.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.cancellable
import tech.elephantclock.privatemail.core.mail.read.FullText
import tech.elephantclock.privatemail.core.mail.read.MessageReader
import tech.elephantclock.privatemail.core.mail.read.ReadResult
import java.io.File

sealed interface ContentResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ContentResult<T>

    /** The message moved or was deleted on the server since the last sync. */
    data object Gone : ContentResult<Nothing>

    /** The account waits for its password (`authProblem`); nothing was tried, to avoid a lockout. */
    data object NeedsSignIn : ContentResult<Nothing>

    data class TooLarge(
        val sizeBytes: Long,
    ) : ContentResult<Nothing>

    data object NoSuchPart : ContentResult<Nothing>

    /** Sign-in or network problem ([error]), or something unexpected (null). */
    data class Failed(
        val error: AuthError?,
    ) : ContentResult<Nothing>
}

/**
 * Fetches what the database doesn't keep, on demand and read-only: a message's full text when it
 * is opened, an attachment when it is tapped (T45). Each call signs in, reads with EXAMINE and
 * `BODY.PEEK`, and signs out. Recent texts are cached in memory, keyed by server location.
 */
class MessageContentRepository(
    private val db: AppDatabase,
    private val connector: ImapConnector,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache =
        object : LinkedHashMap<String, FullText>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FullText>?) = size > CACHE_SIZE
        }

    suspend fun body(messageId: Long): ContentResult<FullText> {
        val message = db.messages().get(messageId) ?: return ContentResult.Gone
        val key = "${message.accountId}/${message.folderPath}/${message.uidValidity}/${message.uid}"
        synchronized(cache) { cache[key] }?.let { return ContentResult.Ok(it) }
        val result =
            withConnection(message) { c ->
                MessageReader.body(c, message.folderPath, message.uidValidity, message.uid)
            }
        if (result is ContentResult.Ok) synchronized(cache) { cache[key] = result.value }
        return result
    }

    /** Downloads attachment [partId] of [messageId] into [target]; returns its size. */
    suspend fun attachment(
        messageId: Long,
        partId: String,
        target: File,
    ): ContentResult<Long> {
        val message = db.messages().get(messageId) ?: return ContentResult.Gone
        return withConnection(message) { c ->
            MessageReader.attachment(c, message.folderPath, message.uidValidity, message.uid, partId, target)
        }
    }

    private suspend fun <T> withConnection(
        message: MessageEntity,
        block: (ImapConnection) -> ReadResult<T>,
    ): ContentResult<T> =
        withContext(io) {
            val account = db.accounts().get(message.accountId) ?: return@withContext ContentResult.Gone
            if (account.authProblem != null) return@withContext ContentResult.NeedsSignIn
            val connection =
                when (val r = connector.connect(account.toModel())) {
                    is MailResult.Failed -> {
                        // Like sync: a wrong password stops further tries until the user fixes it.
                        if (r.error.needsUserAction) {
                            db.accounts().setAuthProblem(
                                account.id,
                                r.error.javaClass.simpleName,
                            )
                        }
                        return@withContext ContentResult.Failed(r.error)
                    }

                    is MailResult.Ok -> {
                        r.value
                    }
                }
            try {
                when (val r = connection.cancellable(io, block)) {
                    is ReadResult.Found -> ContentResult.Ok(r.value)
                    ReadResult.Gone -> ContentResult.Gone
                    ReadResult.NoSuchPart -> ContentResult.NoSuchPart
                    is ReadResult.TooLarge -> ContentResult.TooLarge(r.sizeBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dropped connection, a server error (Angus' MessagingException) or a full disk.
                ContentResult.Failed(AuthError.Unknown(e.message ?: e.javaClass.simpleName))
            } finally {
                withContext(NonCancellable) { runCatching { connection.close() } }
            }
        }

    private companion object {
        const val CACHE_SIZE = 20
    }
}
