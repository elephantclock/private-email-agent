package tech.elephantclock.privatemail.feature.accounts

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.accounts.AccountRemovalHooks
import tech.elephantclock.privatemail.core.data.accounts.AccountRemover
import tech.elephantclock.privatemail.core.data.accounts.HostPort
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.secure.SecretKeys
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountsViewModelTest {
    private class FakeSource : AccountsSource {
        val items =
            MutableStateFlow(
                listOf(
                    AccountItem("a", "anna@a.example", "anna@a.example", 0, false, 100),
                    AccountItem("b", "ben@b.example", "Work", 1, true, null),
                ),
            )
        val calls = mutableListOf<String>()
        var failRemove = false

        override fun accounts() = items

        override suspend fun rename(
            id: String,
            name: String,
        ) {
            calls += "rename:$id:$name"
        }

        override suspend fun setColor(
            id: String,
            colorIndex: Int,
        ) {
            calls += "colour:$id:$colorIndex"
        }

        override suspend fun remove(id: String): Boolean {
            calls += "remove:$id"
            if (failRemove) error("disk full")
            return true
        }
    }

    private val source = FakeSource()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AccountsViewModel(source)

    @Test
    fun `remove needs the confirmation, then reports it`() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            vm.onRemove("a")
            assertEquals(AccountsDialog.ConfirmRemove("a", "anna@a.example"), vm.state.value.dialog)
            assertTrue("nothing removed before the confirm", source.calls.isEmpty())
            vm.onDismissDialog()
            assertTrue(source.calls.isEmpty())
            vm.onRemove("a")
            vm.onRemoveConfirm()
            assertEquals(listOf("remove:a"), source.calls)
            assertEquals(AccountsNotice.REMOVED, vm.state.value.notice)
            assertNull(vm.state.value.removing)
        }

    @Test
    fun `a failed removal says so and leaves the account listed`() =
        runTest {
            source.failRemove = true
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            vm.onRemove("b")
            vm.onRemoveConfirm()
            assertEquals(AccountsNotice.REMOVE_FAILED, vm.state.value.notice)
            assertEquals(2, vm.state.value.accounts.size)
        }

    @Test
    fun `rename starts empty for an unnamed account and saves the text`() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            vm.onRename("a")
            assertEquals(AccountsDialog.Rename("a", "anna@a.example", ""), vm.state.value.dialog)
            vm.onRenameText("Personal")
            vm.onRenameConfirm()
            vm.onRename("b")
            assertEquals("Work", (vm.state.value.dialog as AccountsDialog.Rename).text)
            vm.onColor("b", 4)
            assertEquals(listOf("rename:a:Personal", "colour:b:4"), source.calls)
        }

    @Test
    fun `the Room source trims names, falls back to the address and reads status`() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            val noHooks =
                object : AccountRemovalHooks {
                    override suspend fun stopWork(id: AccountId) = Unit

                    override fun clearNotifications(id: AccountId) = Unit

                    override fun forgetPins(servers: Set<HostPort>) = Unit

                    override fun deleteFiles(messageIds: List<Long>) = Unit
                }
            val room = RoomAccountsSource(db, AccountRemover(db, unusedSecrets(), unusedSettings(), noHooks))
            db.accounts().upsert(ReadingFixtures.work.toEntity(1))
            db.accounts().setAuthProblem(ReadingFixtures.work.id.value, "WrongPassword")
            room.rename(ReadingFixtures.work.id.value, "  Office  ")
            room.setColor(ReadingFixtures.work.id.value, 3)
            val item = room.accounts().first().single()
            assertEquals("Office", item.name)
            assertEquals(3, item.colorIndex)
            assertTrue(item.needsPassword)
            assertNull(item.lastSyncMillis)
            room.rename(ReadingFixtures.work.id.value, " ")
            assertEquals(
                "sam@work.example",
                room
                    .accounts()
                    .first()
                    .single()
                    .name,
            )
            db.close()
        }

    private fun unusedSecrets() =
        SecretStore(
            ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("unused", 0),
            object : SecretKeys {
                override fun getOrCreate(alias: String) = error("unused")

                override fun get(alias: String) = null

                override fun delete(alias: String) = Unit
            },
        )

    private fun unusedSettings() =
        TriageSettingsStore(
            TriageSettingsStore.create(
                CoroutineScope(Dispatchers.IO),
            ) { File.createTempFile("prefs", ".json").also { it.delete() } },
        )
}
