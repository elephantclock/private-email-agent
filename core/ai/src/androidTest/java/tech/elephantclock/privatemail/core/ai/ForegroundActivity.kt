package tech.elephantclock.privatemail.core.ai

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/**
 * ML Kit GenAI serves only the top foreground app ("GenAI API inference is permitted only when
 * the app is the top foreground application"): device tests hold this screen open while they run.
 */
class ForegroundActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Private Email Agent: on-device AI check" })
    }
}
