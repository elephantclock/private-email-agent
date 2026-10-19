package tech.elephantclock.privatemail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.accounts.AccountsRoute
import tech.elephantclock.privatemail.feature.discarded.DiscardedRoute
import tech.elephantclock.privatemail.feature.onboarding.AddAccountRoute
import tech.elephantclock.privatemail.feature.onboarding.FirstRunRoute
import tech.elephantclock.privatemail.feature.onboarding.PersonaliseRoute
import tech.elephantclock.privatemail.feature.rules.RulesRoute
import tech.elephantclock.privatemail.feature.settings.SettingsTab
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.AuthProblemBanner
import tech.elephantclock.privatemail.feature.shell.PlaceholderList
import tech.elephantclock.privatemail.feature.shell.ShellDestination
import tech.elephantclock.privatemail.feature.shell.ShellListPane
import tech.elephantclock.privatemail.notify.NotificationLink
import tech.elephantclock.privatemail.notify.NotificationLinks
import tech.elephantclock.privatemail.notify.RequestNotificationPermission
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

class MainActivity : ComponentActivity() {
    /** The notification the app was opened from; a new one replaces it (single-top launches). */
    private var link by mutableStateOf<NotificationLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) link = NotificationLinks.parse(intent)
        val graph = (application as PrivateMailApp).graph
        setContent {
            PrivateMailTheme {
                MailApp(graph, link)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationLinks.parse(intent)?.let { link = it }
    }
}

/**
 * No account yet: add one (01). A new account then goes through What matters (02) and the first
 * run (03), following its stored onboarding step; after that, the shell. An account whose
 * password stopped working gets a banner that reopens the add-account screen for that address.
 */
@Composable
private fun MailApp(
    graph: MailGraph,
    link: NotificationLink?,
) {
    val context = LocalContext.current
    val onboarding = remember(graph) { graph.onboarding(context) }
    val accounts by graph.database
        .accounts()
        .all()
        .collectAsStateWithLifecycle(initialValue = null)
    val prefs by onboarding.store.all.collectAsStateWithLifecycle(initialValue = null)
    // null: not adding; "": a new account; an address: signing in again to that account.
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    // A fresh add-account form each time: a finished form's ViewModel stays "saved" (T52).
    var addSession by rememberSaveable { mutableIntStateOf(0) }
    val list = accounts
    val stored = prefs
    if (list == null || stored == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val target = adding ?: "".takeIf { list.isEmpty() }
    val onboardingAccount =
        list.firstOrNull { (stored.accounts[it.id]?.step ?: OnboardingStep.PERSONALISE) != OnboardingStep.DONE }
    when {
        target != null -> {
            val viewModel = viewModel(key = "add-account:$target:$addSession") { graph.addAccountViewModel(target) }
            BackHandler(enabled = list.isNotEmpty()) { adding = null }
            AddAccountRoute(
                viewModel,
                onAdded = {
                    adding = null
                    addSession++
                },
                reentry = target.isNotEmpty(),
            )
        }

        onboardingAccount != null -> {
            val id = AccountId(onboardingAccount.id)
            when (stored.accounts[onboardingAccount.id]?.step ?: OnboardingStep.PERSONALISE) {
                OnboardingStep.PERSONALISE -> {
                    val viewModel = viewModel(key = "personalise:${id.value}") { onboarding.personaliseViewModel(id) }
                    PersonaliseRoute(viewModel)
                }

                else -> {
                    val viewModel = viewModel(key = "first-run:${id.value}") { onboarding.firstRunViewModel(id) }
                    FirstRunRoute(viewModel)
                }
            }
        }

        else -> {
            val needsPassword = list.firstOrNull { it.authProblem != null }
            RequestNotificationPermission(enabled = true)
            val discarded: ShellListPane = { destination, _, _ ->
                if (destination == ShellDestination.Discarded) {
                    DiscardedRoute(viewModel(key = "discarded") { graph.storedDiscardedViewModel(context) })
                } else if (destination == ShellDestination.Rules) {
                    SettingsTab(
                        rules = { RulesRoute(viewModel(key = "rules") { graph.rulesViewModel(context) }) },
                        accounts = {
                            AccountsRoute(
                                viewModel(key = "accounts") { graph.accountsViewModel(context) },
                                onAddAccount = {
                                    addSession++
                                    adding = ""
                                },
                                onSignInAgain = { adding = it },
                            )
                        },
                    )
                } else {
                    PlaceholderList(destination)
                }
            }
            // A notification tap restarts the shell at its target (Draft reply opens the email; the
            // reply editor is one tap away in its detail pane).
            val banner: @Composable () -> Unit = {
                if (needsPassword != null) AuthProblemBanner(needsPassword.email) { adding = needsPassword.email }
            }
            val startAt =
                if (link is NotificationLink.Discarded) ShellDestination.Discarded else ShellDestination.Priority
            val openKey =
                when (link) {
                    is NotificationLink.Open -> link.localId.toString()
                    is NotificationLink.DraftReply -> link.localId.toString()
                    else -> null
                }
            key(link) {
                AppShell(
                    listPane = remember(graph) { graph.readingListPane(other = discarded) },
                    detailPane = remember(graph) { graph.flexReadingDetailPane() },
                    banner = banner,
                    initialDestination = startAt,
                    initialOpenKey = openKey,
                )
            }
        }
    }
}
