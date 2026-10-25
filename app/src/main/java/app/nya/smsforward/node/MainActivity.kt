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
import app.nya.smsforward.node.inbox.SmsAppFeature
import app.nya.smsforward.node.inbox.recipientOf
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.service.NodeService
import app.nya.smsforward.node.ui.NodeApp
import app.nya.smsforward.node.ui.NyaTheme
import app.nya.smsforward.node.ui.OpenRequest
import app.nya.smsforward.node.view.RemoteConversation
import app.nya.smsforward.node.view.RemoteMessage

class MainActivity : ComponentActivity() {
    // Bumped every time the app comes back to the foreground, so screens re-check permissions the user may have just
    // changed in the system settings.
    private var resumeTick by mutableIntStateOf(0)

    // "短信应用" feature: the conversation a notification or another app's "send SMS to …" asked for.
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

    // The 「平台」 tab's live stream runs while the app is visible.
    override fun onStart() {
        super.onStart()
        NodeRuntime.get(this).view.setForeground(true)
    }

    override fun onStop() {
        NodeRuntime.get(this).view.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    private fun openRequestOf(intent: Intent?): OpenRequest? {
        if (intent == null) return null
        // A 「平台」 notification: open that platform conversation.
        intent.getStringExtra(EXTRA_PLATFORM_PEER_KEY)?.let { peerKey ->
            val device = intent.getStringExtra(EXTRA_PLATFORM_DEVICE).orEmpty()
            val card = intent.getStringExtra(EXTRA_PLATFORM_CARD)
            val peer = intent.getStringExtra(EXTRA_PLATFORM_PEER).orEmpty()
            val last = RemoteMessage(id = 0, deviceId = device, peer = peer, peerKey = peerKey, cardNumber = card)
            return OpenRequest(0, "", platform = RemoteConversation(device, card, peer, peerKey, last))
        }
        if (!SmsAppFeature.isOn(this)) return null
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
        const val EXTRA_PLATFORM_DEVICE = "platform_device"
        const val EXTRA_PLATFORM_CARD = "platform_card"
        const val EXTRA_PLATFORM_PEER = "platform_peer"
        const val EXTRA_PLATFORM_PEER_KEY = "platform_peer_key"
    }
}
