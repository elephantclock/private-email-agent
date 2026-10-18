package tech.elephantclock.privatemail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrivateMailTheme {
                AppShell()
            }
        }
    }
}
