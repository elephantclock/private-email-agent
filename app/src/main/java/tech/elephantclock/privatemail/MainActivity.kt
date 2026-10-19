package tech.elephantclock.privatemail

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
import tech.elephantclock.privatemail.feature.onboarding.AddAccountRoute
import tech.elephantclock.privatemail.feature.onboarding.FirstRunRoute
import tech.elephantclock.privatemail.feature.onboarding.PersonaliseRoute
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.AuthProblemBanner
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PrivateMailApp).graph
        setContent {
            PrivateMailTheme {
                MailApp(graph)
            }
        }
    }
}

/**
 * No account yet: add one (01). A new account then goes through What matters (02) and the first
 * run (03), following its stored onboarding step; after that, the shell. An account whose
 * password stopped working gets a banner that reopens the add-account screen for that address.
 */
@Composable
private fun MailApp(graph: MailGraph) {
    val context = LocalContext.current
    val onboarding = remember(graph) { graph.onboarding(context) }
    val accounts by graph.database
        .accounts()
        .all()
        .collectAsStateWithLifecycle(initialValue = null)
    val prefs by onboarding.store.all.collectAsStateWithLifecycle(initialValue = null)
    // null: not adding; "": a new account; an address: signing in again to that account.
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
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
            val viewModel = viewModel(key = "add-account:$target") { graph.addAccountViewModel(target) }
            BackHandler(enabled = list.isNotEmpty()) { adding = null }
            AddAccountRoute(viewModel, onAdded = { adding = null }, reentry = target.isNotEmpty())
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
            AppShell(
                banner = {
                    if (needsPassword != null) AuthProblemBanner(needsPassword.email) { adding = needsPassword.email }
                },
            )
        }
    }
}
