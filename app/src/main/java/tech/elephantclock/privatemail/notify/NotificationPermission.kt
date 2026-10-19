package tech.elephantclock.privatemail.notify

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/**
 * Asks for `POST_NOTIFICATIONS` (Android 13+) once, at a moment it makes sense: the coordinator
 * shows this when the shell first appears with at least one account, i.e. right after the
 * user added their first account and there is mail to be alerted about. Never on the
 * add-account screen and never again after an answer (Android itself stops showing the
 * dialog after two refusals; the Rules screen can link to the system settings later).
 */
@Composable
fun RequestNotificationPermission(enabled: Boolean) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* answer recorded below */ }
    LaunchedEffect(enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (!NotificationPermission.shouldAsk(context, enabled)) return@LaunchedEffect
        NotificationPermission.markAsked(context)
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

object NotificationPermission {
    private const val PREFS = "pea_notifications"
    private const val ASKED = "permission_asked"

    fun shouldAsk(
        context: Context,
        enabled: Boolean,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean =
        enabled &&
            sdk >= Build.VERSION_CODES.TIRAMISU &&
            !AndroidTriageNotifier.canPost(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ASKED, true) }
}
