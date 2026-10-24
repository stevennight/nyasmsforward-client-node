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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.migrate.LegacyMigration
import app.nya.smsforward.node.migrate.LegacyStatus
import app.nya.smsforward.node.migrate.MigrationResult
import app.nya.smsforward.node.node.ConnectionKind
import app.nya.smsforward.node.node.NodeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Screen { STATUS, SETTINGS }

private enum class Tab { MESSAGES, FORWARD }

/** A conversation to open, from a notification or another app's "send SMS to …". */
data class OpenRequest(val threadId: Long, val address: String, val draft: String = "", val nonce: Long = System.nanoTime())

/**
 * The forwarding screens; with the "短信应用" feature switched on, a "短信" tab (the phone's own inbox) in front of
 * them. Forwarding works the same either way. The move off the old full edition shows first while it is pending.
 */
@Composable
fun NodeApp(runtime: NodeRuntime, resumeTick: Int, openRequest: OpenRequest? = null) {
    val context = LocalContext.current
    val state by runtime.state.collectAsState()
    // While visible, keep the numbers fresh (a new SMS or an upload finishing changes them from other threads).
    LaunchedEffect(resumeTick) {
        while (true) {
            runtime.refresh()
            delay(3_000)
        }
    }

    var legacy by remember { mutableStateOf<LegacyStatus>(LegacyStatus.None) }
    var migrated by remember { mutableStateOf<MigrationResult?>(null) }
    LaunchedEffect(resumeTick) { legacy = withContext(Dispatchers.IO) { LegacyMigration.detect(context) } }
    if (migrated != null || legacy != LegacyStatus.None) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            MigrationScreen(
                legacy, migrated, runtime,
                onMigrated = { migrated = it },
                onClose = {
                    migrated = null
                    legacy = LegacyStatus.None
                },
            )
        }
        return
    }

    val main: @Composable () -> Unit = {
        if (state.smsApp) FullApp(runtime, resumeTick, openRequest) else ForwardingScreens(runtime, resumeTick)
    }
    if (BuildConfig.LEGACY_FULL) {
        Column(Modifier.fillMaxSize()) {
            LegacyNotice(resumeTick)
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { main() }
        }
    } else {
        main()
    }
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
    var trash by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openRequest) {
        openRequest ?: return@LaunchedEffect
        tab = Tab.MESSAGES
        trash = false
        blocked = false
        threadId = openRequest.threadId
        address = openRequest.address
        draft = openRequest.draft
    }

    if (tab == Tab.MESSAGES && trash) {
        BackHandler { trash = false }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { RecycleBinScreen(runtime, onBack = { trash = false }) }
        return
    }

    if (tab == Tab.MESSAGES && blocked) {
        BackHandler { blocked = false }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { BlockedScreen(runtime, onBack = { blocked = false }) }
        return
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
                    runtime,
                    resumeTick,
                    onOpen = { id, number ->
                        threadId = id
                        address = number
                        draft = ""
                    },
                    onNew = { composing = true },
                    onOpenTrash = { trash = true },
                    onOpenBlocked = { blocked = true },
                )
                Tab.FORWARD -> ForwardingScreens(runtime, resumeTick)
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(selected = tab == Tab.MESSAGES, onClick = { tab = Tab.MESSAGES }, icon = { Icon(Icons.Filled.Email, contentDescription = null) }, label = { Text("短信") })
            NavigationBarItem(selected = tab == Tab.FORWARD, onClick = { tab = Tab.FORWARD }, icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }, label = { Text("转发") })
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
