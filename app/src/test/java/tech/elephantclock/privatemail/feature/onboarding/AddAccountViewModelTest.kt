package tech.elephantclock.privatemail.feature.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.ConnectionTestResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveryResult
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.ConnectionSecurity

@OptIn(ExperimentalCoroutinesApi::class)
class AddAccountViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        discovery: FakeDiscovery = FakeDiscovery(GMAIL),
        connection: FakeConnection = FakeConnection(),
        store: FakeStore = FakeStore(),
        io: TestDispatcher = main,
        initialEmail: String = "",
    ) = AddAccountViewModel(discovery, connection, store, io, clock = {
        1_000L
    }, newId = { "new-id" }, initialEmail = initialEmail)

    private fun AddAccountViewModel.enter(
        email: String,
        password: String = "app-pass-123",
    ) {
        onEmailChange(email)
        onEmailDone()
        onPasswordChange(password)
    }

    @Test
    fun discoveryFillsTheForm() {
        val discovery = FakeDiscovery(GMAIL)
        val vm = vm(discovery)
        vm.enter("Ann@gmail.com")
        vm.onEmailDone() // leaving the field again doesn't look it up again

        val s = vm.state.value
        assertEquals(listOf("Ann@gmail.com"), discovery.calls)
        assertEquals(DiscoveryStatus.Found, s.discovery)
        assertEquals("Gmail", s.providerName)
        assertEquals(ServerFields("imap.gmail.com", "993", ConnectionSecurity.SSL_TLS), s.imap)
        assertEquals(ServerFields("smtp.gmail.com", "465", ConnectionSecurity.SSL_TLS), s.smtp)
        assertEquals("ann@gmail.com", s.username)
        assertTrue(s.note != null)
        assertFalse(s.confirmationPending)
    }

    @Test
    fun discoveryRunsOnTheIoDispatcher() {
        val io = StandardTestDispatcher(main.scheduler)
        val discovery = FakeDiscovery(GMAIL)
        val vm = vm(discovery, io = io)
        vm.enter("ann@gmail.com")
        assertEquals(DiscoveryStatus.Running, vm.state.value.discovery)
        assertTrue(discovery.calls.isEmpty())

        io.scheduler.advanceUntilIdle()

        assertEquals(1, discovery.calls.size)
        assertEquals(DiscoveryStatus.Found, vm.state.value.discovery)
    }

    @Test
    fun nothingFoundOpensManualSettings() {
        val vm = vm(FakeDiscovery(DiscoveryResult.Manual))
        vm.enter("me@tiny.example")
        val s = vm.state.value
        assertEquals(DiscoveryStatus.Manual, s.discovery)
        assertTrue(s.serverSettingsOpen)
        assertEquals("me@tiny.example", s.username)
        assertEquals(ServerFields("", "993", ConnectionSecurity.SSL_TLS), s.imap)
        assertEquals(ServerFields("", "587", ConnectionSecurity.STARTTLS), s.smtp)
    }

    @Test
    fun discoveryCrashFallsBackToManual() {
        val vm = AddAccountViewModel({ error("boom") }, FakeConnection(), FakeStore(), main)
        vm.enter("me@tiny.example")
        assertEquals(DiscoveryStatus.Manual, vm.state.value.discovery)
    }

    @Test
    fun hostsOutsideTheDomainNeedAnOkBeforeAnyPasswordIsSent() {
        val connection = FakeConnection()
        val vm = vm(FakeDiscovery(SRV_ELSEWHERE), connection)
        vm.enter("bob@example.org")
        assertTrue(vm.state.value.confirmationPending)

        vm.onTestConnection()
        vm.onAddAccount()
        assertEquals(0, connection.calls.size)

        vm.onConfirmHosts()
        vm.onTestConnection()
        assertEquals(1, connection.calls.size)
        assertEquals(
            "mail.hoster.example",
            connection.calls
                .single()
                .imap.host,
        )
    }

    @Test
    fun aNewAddressNeedsConfirmingAgain() {
        val connection = FakeConnection()
        val vm = vm(FakeDiscovery(SRV_ELSEWHERE), connection)
        vm.enter("bob@example.org")
        vm.onConfirmHosts()
        vm.enter("bob@example.net")
        assertTrue(vm.state.value.confirmationPending)
        vm.onAddAccount()
        assertEquals(0, connection.calls.size)
    }

    @Test
    fun theSameErrorOnBothServersIsShownOnce() {
        val vm =
            vm(
                connection =
                    FakeConnection(
                        ConnectionTestResult(failed(AuthError.WrongCredentials), failed(AuthError.WrongCredentials)),
                    ),
            )
        vm.enter("ann@gmail.com")
        vm.onTestConnection()
        val p =
            vm.state.value.problems
                .single()
        assertEquals(ServerRole.Both, p.role)
        assertEquals(R.string.error_wrong_credentials, p.explanation.headline.res)
    }

    @Test
    fun differentErrorsAreLabelledPerServer() {
        val result = ConnectionTestResult(IMAP_OK, failed(AuthError.PortBlockedOrTimeout("smtp.gmail.com", 465)))
        val vm = vm(connection = FakeConnection(result))
        vm.enter("ann@gmail.com")
        vm.onAddAccount()
        val p =
            vm.state.value.problems
                .single()
        assertEquals(ServerRole.Outgoing, p.role)
        assertEquals(R.string.error_port_blocked, p.explanation.headline.res)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun trustingCertificatesPinsEachHostAndPortThenSavesThemWithTheAccount() {
        val imapCert = cert("mail.corp.example", 993)
        val smtpCert = cert("mail.corp.example", 465)
        val connection =
            FakeConnection(
                ConnectionTestResult(failed(AuthError.CertificateUntrusted(imapCert)), SMTP_OK),
                ConnectionTestResult(IMAP_OK, failed(AuthError.CertificateUntrusted(smtpCert))),
                OK,
            )
        val store = FakeStore()
        val vm = vm(FakeDiscovery(DiscoveryResult.Manual), connection, store)
        vm.enter("me@corp.example")
        vm.onImapChange(
            vm.state.value.imap
                .copy(host = "mail.corp.example"),
        )
        vm.onSmtpChange(ServerFields("mail.corp.example", "465", ConnectionSecurity.SSL_TLS))

        vm.onAddAccount()
        assertEquals(imapCert, vm.state.value.certificate)
        assertTrue(store.events.isEmpty())

        vm.onTrustCertificate() // re-runs the sign-in with the IMAP pin
        assertEquals(smtpCert, vm.state.value.certificate)
        assertEquals(listOf(imapCert), connection.calls[1].extraPins)

        vm.onTrustCertificate()
        assertEquals(listOf(imapCert, smtpCert), connection.calls[2].extraPins)
        assertTrue(vm.state.value.saved)
        assertEquals(
            listOf("password:new-id", "pin:mail.corp.example:993", "pin:mail.corp.example:465", "upsert:new-id"),
            store.events,
        )
    }

    @Test
    fun refusingACertificateExplainsAndPinsNothing() {
        val c = cert("mail.corp.example", 993)
        val store = FakeStore()
        val connection = FakeConnection(ConnectionTestResult(failed(AuthError.CertificateUntrusted(c)), SMTP_OK))
        val vm = vm(FakeDiscovery(DiscoveryResult.Manual), connection, store)
        vm.enter("me@corp.example")
        vm.onImapChange(
            vm.state.value.imap
                .copy(host = "mail.corp.example"),
        )
        vm.onSmtpChange(
            vm.state.value.smtp
                .copy(host = "mail.corp.example"),
        )
        vm.onAddAccount()

        vm.onRejectCertificate()

        assertNull(vm.state.value.certificate)
        val p =
            vm.state.value.problems
                .single()
        assertEquals(ServerRole.Incoming, p.role)
        assertEquals(R.string.error_certificate_untrusted, p.explanation.headline.res)
        assertEquals(1, connection.calls.size)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun savingStoresThePasswordBeforeTheAccountRowAndThenForgetsIt() {
        val store = FakeStore()
        val vm = vm(store = store)
        vm.enter("ann@gmail.com", password = "app-pass-123")
        vm.onAddAccount()

        assertTrue(vm.state.value.saved)
        assertEquals(listOf("password:new-id", "upsert:new-id"), store.events)
        assertEquals(Password("app-pass-123"), store.passwords[AccountId("new-id")])
        val (account, created) = store.accounts.single()
        assertEquals("ann@gmail.com", account.email.email)
        assertEquals("gmail", account.providerId)
        assertEquals(1_000L, created)
        assertEquals("", vm.password.value)
    }

    @Test
    fun signingInAgainUpdatesTheExistingAccount() {
        val store = FakeStore(mapOf("ann@gmail.com" to ExistingAccount(AccountId("old-id"), 42L)))
        val vm = vm(store = store, initialEmail = "ann@gmail.com")
        vm.onPasswordChange("new-app-pass")
        vm.onAddAccount()

        assertEquals(listOf("password:old-id", "upsert:old-id"), store.events)
        assertEquals(42L, store.accounts.single().second)
    }

    @Test
    fun aFailedSaveKeepsThePasswordForAnotherTry() {
        val store = FakeStore().apply { failUpsert = true }
        val vm = vm(store = store)
        vm.enter("ann@gmail.com", password = "app-pass-123")
        vm.onAddAccount()
        assertFalse(vm.state.value.saved)
        assertEquals(
            R.string.error_save_failed,
            vm.state.value.problems
                .single()
                .explanation.headline.res,
        )
        assertEquals("app-pass-123", vm.password.value)
    }

    @Test
    fun thePasswordIsNeverPartOfTheUiState() {
        val vm = vm()
        vm.enter("ann@gmail.com", password = "hunter2-secret")
        vm.onTestConnection()
        assertFalse(
            vm.state.value
                .toString()
                .contains("hunter2-secret"),
        )
    }

    @Test
    fun switchingSecurityMovesAStandardPort() {
        val vm = vm()
        vm.enter("ann@gmail.com")
        vm.onSmtpChange(
            vm.state.value.smtp
                .copy(security = ConnectionSecurity.STARTTLS),
        )
        assertEquals("587", vm.state.value.smtp.port)
        vm.onImapChange(
            vm.state.value.imap
                .copy(port = "1993"),
        )
        vm.onImapChange(
            vm.state.value.imap
                .copy(security = ConnectionSecurity.STARTTLS),
        )
        assertEquals("1993", vm.state.value.imap.port) // a custom port stays
    }
}
