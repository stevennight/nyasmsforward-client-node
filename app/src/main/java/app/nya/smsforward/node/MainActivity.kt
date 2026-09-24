package app.nya.smsforward.node

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nya.smsforward.node.inbox.recipientOf
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.service.NodeService
import app.nya.smsforward.node.ui.NodeApp
import app.nya.smsforward.node.ui.NyaTheme
import app.nya.smsforward.node.ui.OpenRequest

class MainActivity : ComponentActivity() {
    // Bumped every time the app comes back to the foreground, so screens re-check permissions the user may have just
    // changed in the system settings.
    private var resumeTick by mutableIntStateOf(0)

    // Full edition: the conversation a notification or another app's "send SMS to …" asked for.
    private var openRequest by mutableStateOf<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = NodeRuntime.get(this)
        // The app is in the foreground now, which is when Android lets us start the send channel's service.
        NodeService.sync(this)
        if (savedInstanceState == null) openRequest = openRequestOf(intent)
        setContent {
            NyaTheme {
                NodeApp(runtime, resumeTick, openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestOf(intent)?.let { openRequest = it }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun openRequestOf(intent: Intent?): OpenRequest? {
        if (intent == null || !BuildConfig.FULL_EDITION) return null
        if (intent.hasExtra(EXTRA_ADDRESS)) {
            return OpenRequest(intent.getLongExtra(EXTRA_THREAD_ID, 0), intent.getStringExtra(EXTRA_ADDRESS).orEmpty())
        }
        if (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            val address = recipientOf(intent.data) ?: return null
            val body = intent.getStringExtra("sms_body")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.data?.schemeSpecificPart?.substringAfter("?body=", "")?.takeIf { it.isNotEmpty() }
                ?: ""
            return OpenRequest(0, address, body)
        }
        return null
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_ADDRESS = "address"
    }
}
