package tech.elephantclock.privatemail.feature.inbox

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.core.data.db.FolderSummary
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.db.TriageCounts
import tech.elephantclock.privatemail.core.data.db.messageEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.Attachment
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource
import java.time.ZoneOffset
import java.util.Locale

/** Synthetic mail for list, detail and reply tests. No real addresses or content. */
object ReadingFixtures {
    /** 18 Oct 2026, 16:00 UTC. */
    const val NOW = 1_792_339_200_000L
    val times = TimeLabels(clock = { NOW }, zone = ZoneOffset.UTC, locale = Locale.UK)

    fun account(
        id: String,
        email: String,
    ): Account =
        Account(
            id = AccountId(id),
            email = Address.of(email, "Sam Tester"),
            displayName = id,
            username = email,
            imap = ServerConfig("imap.example.com", 993, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.example.com", 465, ConnectionSecurity.SSL_TLS),
        )

    val work = account("acc-work", "sam@work.example")
    val home = account("acc-home", "sam@home.example")
    val accounts: List<AccountEntity> = listOf(work.toEntity(1), home.toEntity(2))

    fun header(
        uid: Long,
        from: String,
        subject: String,
        minutesAgo: Long,
        account: Account = work,
        folder: String = "INBOX",
        seen: Boolean = false,
    ) = MessageHeader(
        ref = MessageRef(account.id, folder, 7, uid),
        messageId = "<m$uid@sender.example>",
        from = Address.of("person$uid@sender.example", from),
        subject = subject,
        sentAtMillis = NOW - minutesAgo * 60_000 - 1_000,
        receivedAtMillis = NOW - minutesAgo * 60_000,
        to = listOf(account.email),
        references = listOf("<root$uid@sender.example>"),
        flags = if (seen) setOf(MessageFlag.SEEN) else emptySet(),
        sizeBytes = 2_000,
    )

    fun entity(
        id: Long,
        from: String,
        subject: String,
        preview: String,
        minutesAgo: Long,
        account: Account = work,
        folder: String = "INBOX",
        seen: Boolean = false,
        attachments: List<Attachment> = emptyList(),
    ): MessageEntity {
        val h = header(id, from, subject, minutesAgo, account, folder, seen)
        return messageEntity(h, MessageBody(h.ref, preview, isTruncated = false, attachments = attachments))
            .copy(id = id)
    }

    fun withVerdict(
        entity: MessageEntity,
        importance: Importance?,
        summary: String? = null,
    ) = MessageWithVerdict(
        entity,
        importance?.let {
            Verdict.keep(it, VerdictSource.RULE, "Replied before").copy(summary = summary).toEntity(entity.id, NOW)
        },
    )

    val priority: List<MessageWithVerdict> =
        listOf(
            withVerdict(
                entity(1, "Partner office", "Q3 partner payouts", "Please confirm the payout figures by Monday.", 58),
                Importance.URGENT,
            ),
            withVerdict(
                entity(
                    2,
                    "Server monitor",
                    "Duplicated payments",
                    "Three duplicate orders were listed overnight.",
                    110,
                ),
                Importance.URGENT,
            ),
            withVerdict(
                entity(
                    3,
                    "Customer",
                    "Paid tour not showing",
                    "I paid for a tour but it is not in the app; I travel tomorrow.",
                    237,
                    seen = true,
                ),
                Importance.HIGH,
            ),
            withVerdict(
                entity(
                    4,
                    "Prospective client",
                    "Chatbot for our hotels",
                    "We would like a proposal and a call next week for our 12 properties.",
                    319,
                    account = home,
                    attachments = listOf(Attachment("2", "brief.pdf", "application/pdf", 48_000)),
                ),
                Importance.HIGH,
                summary = "Wants a proposal and a call next week; 12 properties.",
            ),
            withVerdict(
                entity(
                    5,
                    "Code review",
                    "PR #214 review requested",
                    "You are the required reviewer.",
                    2_000,
                    seen = true,
                ),
                Importance.NORMAL,
            ),
        )
}

/** In-memory [InboxSource]; records what the lists asked for. */
class FakeInboxSource(
    rows: List<MessageWithVerdict> = ReadingFixtures.priority,
    accounts: List<AccountEntity> = ReadingFixtures.accounts,
) : InboxSource {
    val rows = MutableStateFlow(rows)
    val accountList = MutableStateFlow(accounts)
    val folderLists = mutableMapOf<String, List<FolderSummary>>()
    var counts = TriageCounts(0, 0, 0)
    val priorityRequests = mutableListOf<String?>()
    val folderRequests = mutableListOf<Pair<String, String>>()
    val countRequests = mutableListOf<Pair<Long, String?>>()
    var refreshes = 0
    var refreshResult: () -> RefreshOutcome = { RefreshOutcome.DONE }

    override fun accounts(): Flow<List<AccountEntity>> = accountList

    override fun priorityCounts(): Flow<Map<String, Int>> =
        rows.map { list -> list.groupingBy { it.message.accountId }.eachCount() }

    override fun priority(accountId: String?): Flow<PagingData<MessageWithVerdict>> {
        priorityRequests += accountId
        return rows.map { list -> staticPage(list.filter { accountId == null || it.message.accountId == accountId }) }
    }

    override fun folder(
        accountId: String,
        path: String,
    ): Flow<PagingData<MessageWithVerdict>> {
        folderRequests += accountId to path
        return rows.map { list ->
            staticPage(list.filter { it.message.accountId == accountId && it.message.folderPath == path })
        }
    }

    override fun folders(accountId: String): Flow<List<FolderSummary>> = flowOf(folderLists[accountId].orEmpty())

    override fun triagedSince(
        sinceMillis: Long,
        accountId: String?,
    ): Flow<TriageCounts> {
        countRequests += sinceMillis to accountId
        return flowOf(counts)
    }

    override suspend fun search(text: String): List<MessageWithVerdict> =
        rows.value.filter { it.message.subject.contains(text, ignoreCase = true) }

    override suspend fun refresh(): RefreshOutcome {
        refreshes++
        return refreshResult()
    }
}

private val done = LoadState.NotLoading(endOfPaginationReached = true)

/** A finished, single-page list: no spinners in screenshots, and `asSnapshot` returns at once. */
fun <T : Any> staticPage(items: List<T>): PagingData<T> = PagingData.from(items, LoadStates(done, done, done))
