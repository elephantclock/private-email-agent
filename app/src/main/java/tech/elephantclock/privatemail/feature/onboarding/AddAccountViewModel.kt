package tech.elephantclock.privatemail.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.ConnectionTestResult
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveryResult
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.AuthType
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.util.UUID

/**
 * Add-account flow: discovery → (confirm hosts) → test → save. Every path that sends the
 * password goes through [signIn], which refuses while [AddAccountUiState.confirmationPending].
 * The password lives only in [password], never in the UI state or saved instance state, and is
 * cleared once the account is saved.
 */
class AddAccountViewModel(
    private val discovery: SettingsDiscovery,
    private val connection: ConnectionCheck,
    private val store: AccountStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    initialEmail: String = "",
) : ViewModel(),
    AddAccountActions {
    private val _state = MutableStateFlow(AddAccountUiState(email = initialEmail))
    val state: StateFlow<AddAccountUiState> = _state.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    /** Certificates trusted during this setup; saved with the account. */
    private val pendingPins = mutableListOf<CertificateInfo>()
    private var discoveredFor: String? = null
    private var discoveryJob: Job? = null
    private var lastAction: (() -> Unit)? = null

    init {
        if (looksLikeEmail(initialEmail)) onEmailDone()
    }

    override fun onEmailChange(email: String) {
        val changed = email.trim() != _state.value.email.trim()
        _state.update { it.copy(email = email) }
        if (changed && discoveredFor != null) {
            discoveryJob?.cancel()
            discoveredFor = null
            pendingPins.clear()
            _state.update {
                AddAccountUiState(
                    email = email,
                    imap = it.imap,
                    smtp = it.smtp,
                    serverSettingsOpen = it.serverSettingsOpen,
                )
            }
        }
    }

    /** The user left the email field or pressed Next: look up settings for this address once. */
    override fun onEmailDone() {
        val email = _state.value.email.trim()
        if (!looksLikeEmail(email) || email == discoveredFor) return
        discoveredFor = email
        discoveryJob?.cancel()
        _state.update { it.copy(discovery = DiscoveryStatus.Running, problems = emptyList(), testPassed = false) }
        discoveryJob =
            viewModelScope.launch {
                // A crash in discovery must not end the flow: fall back to manual settings.
                val result =
                    withContext(io) { runCatching { discovery.discover(email) }.getOrDefault(DiscoveryResult.Manual) }
                if (_state.value.email.trim() != email) return@launch
                _state.update { it.applying(result, email) }
            }
    }

    private fun AddAccountUiState.applying(
        result: DiscoveryResult,
        email: String,
    ): AddAccountUiState =
        when (result) {
            is DiscoveryResult.Found -> {
                val s = result.settings
                copy(
                    discovery = DiscoveryStatus.Found,
                    providerId = s.providerId,
                    providerName = s.providerName,
                    note = s.note,
                    passwordSupported = s.passwordSupported,
                    username = s.username,
                    imap = ServerFields.of(s.imap),
                    smtp = ServerFields.of(s.smtp),
                    confirmationPending = s.needsConfirmation,
                    serverSettingsOpen = serverSettingsOpen || s.needsConfirmation,
                )
            }

            DiscoveryResult.Manual -> {
                copy(
                    discovery = DiscoveryStatus.Manual,
                    providerId = null,
                    providerName = null,
                    note = null,
                    passwordSupported = true,
                    username = email,
                    imap = ServerFields(port = "993", security = ConnectionSecurity.SSL_TLS),
                    smtp = ServerFields(port = "587", security = ConnectionSecurity.STARTTLS),
                    confirmationPending = false,
                    serverSettingsOpen = true,
                )
            }
        }

    override fun onPasswordChange(password: String) {
        _password.value = password
        _state.update { it.copy(testPassed = false) }
    }

    override fun onUsernameChange(username: String) = edit { it.copy(username = username) }

    override fun onImapChange(fields: ServerFields) =
        edit { it.copy(imap = withDefaultPort(it.imap, fields, IMAP_PORTS)) }

    override fun onSmtpChange(fields: ServerFields) =
        edit { it.copy(smtp = withDefaultPort(it.smtp, fields, SMTP_PORTS)) }

    override fun onToggleServerSettings() = _state.update { it.copy(serverSettingsOpen = !it.serverSettingsOpen) }

    /** The user saw the DNS-derived hosts and accepts them. */
    override fun onConfirmHosts() = _state.update { it.copy(confirmationPending = false) }

    override fun onTestConnection() = signIn(save = false)

    override fun onAddAccount() = signIn(save = true)

    override fun onTrustCertificate() {
        val cert = _state.value.certificate ?: return
        pendingPins.removeAll { it.host.equals(cert.host, ignoreCase = true) && it.port == cert.port }
        pendingPins += cert
        _state.update { it.copy(certificate = null) }
        lastAction?.invoke()
    }

    override fun onRejectCertificate() {
        val cert = _state.value.certificate ?: return
        _state.update {
            it.copy(
                certificate = null,
                problems = listOf(ServerProblem(roleOf(cert), AuthError.CertificateUntrusted(cert).explain())),
            )
        }
    }

    private fun edit(change: (AddAccountUiState) -> AddAccountUiState) =
        _state.update { change(it).copy(testPassed = false, problems = emptyList()) }

    /** The only way the password leaves the view model. */
    private fun signIn(save: Boolean) {
        val s = _state.value
        val password = _password.value
        if (s.busy != null || s.confirmationPending || s.discovery == DiscoveryStatus.Running) return
        if (!s.emailValid || password.isEmpty()) return
        val imap = s.imap.toConfig() ?: return
        val smtp = s.smtp.toConfig() ?: return
        if (s.username.isBlank()) return
        lastAction = { signIn(save) }
        _state.update { it.copy(busy = Busy.Testing, problems = emptyList(), testPassed = false) }
        viewModelScope.launch {
            val credentials = MailCredentials(s.username.trim(), Password(password))
            val pins = pendingPins.toList()
            val result = withContext(io) { connection.test(imap, smtp, credentials, pins) }
            val untrusted = result.untrustedCertificate()
            when {
                untrusted != null -> {
                    _state.update { it.copy(busy = null, certificate = untrusted) }
                }

                !result.ok -> {
                    _state.update { it.copy(busy = null, problems = result.problems()) }
                }

                !save -> {
                    _state.update { it.copy(busy = null, testPassed = true) }
                }

                else -> {
                    _state.update { it.copy(busy = Busy.Saving, testPassed = true) }
                    save(s, imap, smtp, Password(password))
                }
            }
        }
    }

    /**
     * Password first, then pins, then the account row: the row's arrival starts IDLE and sync
     * straight away, and they need both to sign in (a missing password would set `authProblem`).
     */
    private suspend fun save(
        s: AddAccountUiState,
        imap: ServerConfig,
        smtp: ServerConfig,
        password: Password,
    ) {
        val address = Address.of(s.email)
        val outcome =
            runCatching {
                withContext(io) {
                    val existing = store.find(address.email)
                    val id = existing?.id ?: AccountId(newId())
                    store.savePassword(id, password)
                    pendingPins.forEach(store::trust)
                    val account =
                        Account(
                            id = id,
                            email = address,
                            displayName = address.email,
                            username = s.username.trim(),
                            imap = imap,
                            smtp = smtp,
                            authType = AuthType.PASSWORD,
                            providerId = s.providerId,
                        )
                    store.upsert(account, existing?.createdAtMillis ?: clock())
                }
            }
        outcome
            .onSuccess {
                pendingPins.clear()
                _password.value = ""
                _state.update { it.copy(busy = null, saved = true) }
            }.onFailure { e ->
                _state.update {
                    val explanation =
                        ErrorExplanation(UiText(R.string.error_save_failed), detail = e.javaClass.simpleName)
                    it.copy(busy = null, problems = listOf(ServerProblem(ServerRole.Both, explanation)))
                }
            }
    }

    override fun onCleared() {
        _password.value = ""
    }

    private fun ConnectionTestResult.untrustedCertificate(): CertificateInfo? =
        listOf(imap, smtp)
            .filterIsInstance<MailResult.Failed>()
            .map { it.error }
            .filterIsInstance<AuthError.CertificateUntrusted>()
            .firstOrNull()
            ?.certificate

    private fun ConnectionTestResult.problems(): List<ServerProblem> {
        val i = (imap as? MailResult.Failed)?.error
        val o = (smtp as? MailResult.Failed)?.error
        return when {
            i != null && i == o -> {
                listOf(ServerProblem(ServerRole.Both, i.explain()))
            }

            else -> {
                listOfNotNull(
                    i?.let { ServerProblem(ServerRole.Incoming, it.explain()) },
                    o?.let { ServerProblem(ServerRole.Outgoing, it.explain()) },
                )
            }
        }
    }

    private fun roleOf(cert: CertificateInfo): ServerRole {
        val s = _state.value
        val isImap =
            s.imap.host
                .trim()
                .equals(cert.host, ignoreCase = true) && s.imap.port.trim() == cert.port.toString()
        return if (isImap) ServerRole.Incoming else ServerRole.Outgoing
    }

    private companion object {
        val IMAP_PORTS = mapOf(ConnectionSecurity.SSL_TLS to "993", ConnectionSecurity.STARTTLS to "143")
        val SMTP_PORTS = mapOf(ConnectionSecurity.SSL_TLS to "465", ConnectionSecurity.STARTTLS to "587")

        /** Switching security moves a standard port along with it (993 ↔ 143, 465 ↔ 587). */
        fun withDefaultPort(
            old: ServerFields,
            new: ServerFields,
            defaults: Map<ConnectionSecurity, String>,
        ): ServerFields =
            if (old.security != new.security && new.port.trim() == defaults[old.security]) {
                new.copy(port = defaults.getValue(new.security))
            } else {
                new
            }
    }
}
