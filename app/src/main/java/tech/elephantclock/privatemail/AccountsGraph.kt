package tech.elephantclock.privatemail

import android.content.Context
import androidx.work.WorkManager
import tech.elephantclock.privatemail.core.data.accounts.AccountRemover
import tech.elephantclock.privatemail.feature.accounts.AccountsViewModel
import tech.elephantclock.privatemail.feature.accounts.AndroidRemovalHooks
import tech.elephantclock.privatemail.feature.accounts.RoomAccountsSource

// The Accounts screen's wiring (T52), kept out of MailGraph so lanes don't collide.

/** Removes an account from this device only: database, password and key, pins, settings, work, notifications, files. */
fun MailGraph.accountRemover(context: Context): AccountRemover =
    AccountRemover(
        db = database,
        secrets = secrets,
        settings = onboarding(context).store,
        hooks = AndroidRemovalHooks(context.applicationContext, WorkManager.getInstance(context), certificatePins),
    )

fun MailGraph.accountsViewModel(context: Context): AccountsViewModel =
    AccountsViewModel(RoomAccountsSource(database, accountRemover(context)))
