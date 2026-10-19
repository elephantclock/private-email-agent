package tech.elephantclock.privatemail.feature.detail

import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.repo.ContentResult
import tech.elephantclock.privatemail.core.data.repo.MessageContentRepository
import tech.elephantclock.privatemail.core.mail.read.FullText
import java.io.File

/** What the detail screen reads. [RoomDetailSource] in the app; fakes in tests. */
interface DetailSource {
    fun message(id: Long): Flow<MessageWithVerdict?>

    fun accounts(): Flow<List<AccountEntity>>

    /** The full text, fetched from the server read-only (BODY.PEEK). */
    suspend fun body(id: Long): ContentResult<FullText>

    /** Downloads an attachment into [target] (in the app's cache). */
    suspend fun attachment(
        id: Long,
        partId: String,
        target: File,
    ): ContentResult<Long>

    /** Where attachments of message [id] are kept: `cacheDir/attachments/<id>/`. */
    fun attachmentDir(id: Long): File
}

class RoomDetailSource(
    private val db: AppDatabase,
    private val content: MessageContentRepository,
    private val cacheDir: File,
) : DetailSource {
    override fun message(id: Long) = db.reading().message(id)

    override fun accounts() = db.accounts().all()

    override suspend fun body(id: Long) = content.body(id)

    override suspend fun attachment(
        id: Long,
        partId: String,
        target: File,
    ) = content.attachment(id, partId, target)

    override fun attachmentDir(id: Long) = AttachmentFiles.dirFor(cacheDir, id)
}
