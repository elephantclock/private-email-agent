package tech.elephantclock.privatemail.feature.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.FakeAiAvailability
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.repo.ContentResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.read.FullText
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import tech.elephantclock.privatemail.ui.components.ImportanceLevel
import java.io.File

class FakeDetailSource(
    message: MessageWithVerdict? = ReadingFixtures.priority[3],
    private val dir: File,
) : DetailSource {
    val message = MutableStateFlow(message)
    var bodyResult: suspend () -> ContentResult<FullText> = {
        ContentResult.Ok(FullText("Hello Sam,\nWe would like a proposal.", fromHtml = false, truncated = false))
    }
    var attachmentResult: (File) -> ContentResult<Long> = { f ->
        f.writeText("%PDF")
        ContentResult.Ok(4)
    }
    var bodyCalls = 0
    val attachmentCalls = mutableListOf<String>()

    override fun message(id: Long): Flow<MessageWithVerdict?> = message

    override fun accounts(): Flow<List<AccountEntity>> = flowOf(ReadingFixtures.accounts)

    override suspend fun body(id: Long): ContentResult<FullText> {
        bodyCalls++
        return bodyResult()
    }

    override suspend fun attachment(
        id: Long,
        partId: String,
        target: File,
    ): ContentResult<Long> {
        attachmentCalls += partId
        return attachmentResult(target)
    }

    override fun attachmentDir(id: Long) = File(dir, "attachments/$id")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()
    private lateinit var source: FakeDetailSource

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeDetailSource(dir = temp.root)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(ai: tech.elephantclock.privatemail.core.ai.AiAvailability = NoAiAvailability()) =
        DetailViewModel(4, source, ai, ReadingFixtures.times)

    @Test
    fun `shows stored headers and triage at once and fetches the full text on open`() =
        runTest {
            val s = vm().state.value
            assertEquals("Chatbot for our hotels", s.header!!.subject)
            assertEquals("sam@home.example", s.header!!.account) // two accounts: say which one
            assertEquals(ImportanceLevel.High, s.triage!!.level)
            assertEquals("Replied before", s.triage!!.reason)
            assertEquals("Wants a proposal and a call next week; 12 properties.", s.brief)
            assertTrue(s.briefFromAi)
            assertEquals(BodyState.Loaded("Hello Sam,\nWe would like a proposal.", false, false), s.body)
            assertEquals(1, source.bodyCalls)
            assertFalse(s.originalExpanded)
        }

    @Test
    fun `without AI there are no next steps and the screen says so`() =
        runTest {
            val s = vm().state.value
            assertFalse(s.aiAvailable)
            assertFalse(s.showNextSteps)
        }

    @Test
    fun `AI availability is read from the summarizer status`() =
        runTest {
            val ai =
                FakeAiAvailability(
                    ModelStatus.Unavailable(tech.elephantclock.privatemail.core.ai.UnavailableReason.NOT_READY),
                )
            val vm = vm(ai)
            assertFalse(vm.state.value.aiAvailable)
            ai.set(AiFeature.SUMMARIZE, ModelStatus.Available)
            assertTrue(vm.state.value.aiAvailable)
        }

    @Test
    fun `without a summary the brief is the start of the stored preview`() =
        runTest {
            source.message.value = ReadingFixtures.priority[0]
            val s = vm().state.value
            assertEquals("Please confirm the payout figures by Monday.", s.brief)
            assertFalse(s.briefFromAi)
        }

    @Test
    fun `attachments download only on tap, into the cache, then open`() =
        runTest {
            val vm = vm()
            assertEquals(
                listOf("brief.pdf"),
                vm.state.value.attachments
                    .map { it.name },
            )
            assertTrue(source.attachmentCalls.isEmpty())

            vm.openAttachment("2")
            val open = vm.state.value.open!!
            assertEquals(File(temp.root, "attachments/4/2/brief.pdf"), open.file)
            assertEquals("application/pdf", open.mimeType)
            vm.opened(launched = true)
            assertNull(vm.state.value.open)

            vm.openAttachment("2") // already downloaded: no second fetch
            assertEquals(listOf("2"), source.attachmentCalls)
        }

    @Test
    fun `no app for the type, or a failed download, is explained on the attachment`() =
        runTest {
            val vm = vm()
            vm.openAttachment("2")
            vm.opened(launched = false)
            assertEquals(
                Problem.NO_APP,
                vm.state.value.attachments
                    .single()
                    .problem,
            )

            File(temp.root, "attachments/4/2/brief.pdf").delete()
            source.attachmentResult = { ContentResult.Failed(AuthError.Unknown("reset")) }
            vm.openAttachment("2")
            assertEquals(
                Problem.OFFLINE,
                vm.state.value.attachments
                    .single()
                    .problem,
            )
            assertNull(vm.state.value.open)
        }

    @Test
    fun `body problems are shown, and only connection problems offer a retry that refetches`() =
        runTest {
            val gate = CompletableDeferred<ContentResult<FullText>>()
            source.bodyResult = { ContentResult.Failed(AuthError.PortBlockedOrTimeout("imap.example.com", 993)) }
            val vm = vm()
            assertEquals(BodyState.Failed(Problem.OFFLINE), vm.state.value.body)
            source.bodyResult = { gate.await() }
            vm.retryBody()
            assertEquals(BodyState.Loading, vm.state.value.body)
            gate.complete(ContentResult.Gone)
            assertEquals(BodyState.Failed(Problem.GONE), vm.state.value.body)
            assertEquals(2, source.bodyCalls)
        }

    @Test
    fun `a message deleted while open shows as missing`() =
        runTest {
            val vm = vm()
            source.message.value = null
            assertTrue(vm.state.value.missing)
        }
}
