package app.nya.smsforward.node.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.node.ConnectionKind
import app.nya.smsforward.node.node.NodeRuntime
import kotlinx.coroutines.delay

private enum class Screen { STATUS, SETTINGS }

private enum class Tab { MESSAGES, FORWARD }

/** A conversation to open, from a notification or another app's "send SMS to …". */
data class OpenRequest(val threadId: Long, val address: String, val draft: String = "", val nonce: Long = System.nanoTime())

/**
 * The lite edition is the forwarding screens only. The full edition adds a "短信" tab (the phone's own inbox) in front
 * of them; forwarding works the same in both.
 */
@Composable
fun NodeApp(runtime: NodeRuntime, resumeTick: Int, openRequest: OpenRequest? = null) {
    // While visible, keep the numbers fresh (a new SMS or an upload finishing changes them from other threads).
    LaunchedEffect(resumeTick) {
        while (true) {
            runtime.refresh()
            delay(3_000)
        }
    }
    if (BuildConfig.FULL_EDITION) FullApp(runtime, resumeTick, openRequest) else ForwardingScreens(runtime, resumeTick)
}

@Composable
private fun ForwardingScreens(runtime: NodeRuntime, resumeTick: Int) {
    val state by runtime.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.STATUS) }
    when (state.connection) {
        ConnectionKind.PAIRED -> when (screen) {
            Screen.STATUS -> StatusScreen(state, runtime, resumeTick, onOpenSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> {
                BackHandler { screen = Screen.STATUS }
                SettingsScreen(state, runtime, resumeTick, onBack = { screen = Screen.STATUS })
            }
        }
        else -> PairScreen(state, runtime)
    }
}

@Composable
private fun FullApp(runtime: NodeRuntime, resumeTick: Int, openRequest: OpenRequest?) {
    var tab by rememberSaveable { mutableStateOf(Tab.MESSAGES) }
    var threadId by rememberSaveable { mutableStateOf<Long?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openRequest) {
        openRequest ?: return@LaunchedEffect
        tab = Tab.MESSAGES
        threadId = openRequest.threadId
        address = openRequest.address
        draft = openRequest.draft
    }

    val open = threadId
    if (tab == Tab.MESSAGES && open != null) {
        BackHandler { threadId = null }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Keyed so switching straight to another conversation (from a notification) starts with fresh state.
            key(open, address, openRequest?.nonce) {
                ThreadScreen(runtime, open, address, draft, onBack = { threadId = null })
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // The navigation bar pads itself for the system bar below it.
        Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).consumeWindowInsets(WindowInsets.navigationBars)) {
            when (tab) {
                Tab.MESSAGES -> InboxScreen(
                    resumeTick,
                    onOpen = { id, number ->
                        threadId = id
                        address = number
                        draft = ""
                    },
                    onNew = { composing = true },
                )
                Tab.FORWARD -> ForwardingScreens(runtime, resumeTick)
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(selected = tab == Tab.MESSAGES, onClick = { tab = Tab.MESSAGES }, icon = { Text("✉") }, label = { Text("短信") })
            NavigationBarItem(selected = tab == Tab.FORWARD, onClick = { tab = Tab.FORWARD }, icon = { Text("⇄") }, label = { Text("转发") })
        }
    }

    if (composing) {
        NewMessageDialog(onDismiss = { composing = false }, onStart = { number ->
            composing = false
            threadId = 0
            address = number
            draft = ""
        })
    }
}
