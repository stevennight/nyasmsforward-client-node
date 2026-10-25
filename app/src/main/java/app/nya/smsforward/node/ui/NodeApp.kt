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
import androidx.compose.material.icons.filled.Search
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
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.view.RemoteConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Screen {
    STATUS,
    SETTINGS,

    /** The "短信应用" page opened from the pairing screen, for using the app as an SMS app without a server. */
    SMS_APP,

    /** The 「查看」 page opened from the pairing screen: only viewing, no receiver pairing. */
    VIEW,
}

private enum class Tab { MESSAGES, PLATFORM, FORWARD }

/** A conversation to open, from a notification or another app's "send SMS to …". */
data class OpenRequest(
    val threadId: Long,
    val address: String,
    val draft: String = "",
    /** A 「平台」 conversation instead (from its notification). */
    val platform: RemoteConversation? = null,
    val nonce: Long = System.nanoTime(),
)

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

    // Kept here, above both layouts: switching the "短信应用" feature swaps FullApp and the plain forwarding screens, and
    // the user should stay where they are (normally on the settings page with that switch). Starting on the forwarding
    // tab when the feature is off means turning it on lands there, not in the inbox.
    var screen by rememberSaveable { mutableStateOf(Screen.STATUS) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val viewState by runtime.view.state.collectAsState()
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                state.smsApp -> Tab.MESSAGES
                viewState.enabled -> Tab.PLATFORM
                else -> Tab.FORWARD
            },
        )
    }
    val forwarding: @Composable () -> Unit = {
        ForwardingScreens(state, runtime, resumeTick, screen, { screen = it }, settingsPage, { settingsPage = it })
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
        val tabs = listOfNotNull(Tab.MESSAGES.takeIf { state.smsApp }, Tab.PLATFORM.takeIf { viewState.enabled }, Tab.FORWARD)
        if (tabs.size > 1) FullApp(runtime, resumeTick, openRequest, tabs, tab, { tab = it }, forwarding, onOpenViewSettings = { screen = Screen.VIEW }) else forwarding()
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
private fun ForwardingScreens(
    state: UiState,
    runtime: NodeRuntime,
    resumeTick: Int,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    settingsPage: SettingsPage?,
    onSettingsPage: (SettingsPage?) -> Unit,
) {
    when {
        screen == Screen.SMS_APP -> {
            BackHandler { onScreen(Screen.STATUS) }
            SettingsPageFrame(SettingsPage.SMS_APP.title, onBack = { onScreen(Screen.STATUS) }) { SmsAppSections(state, runtime) }
        }
        screen == Screen.VIEW -> {
            BackHandler { onScreen(Screen.STATUS) }
            SettingsPageFrame(SettingsPage.VIEW.title, onBack = { onScreen(Screen.STATUS) }) { ViewSections(runtime) }
        }
        state.connection != ConnectionKind.PAIRED -> PairScreen(
            state, runtime,
            onOpenSmsApp = { onScreen(Screen.SMS_APP) },
            onOpenView = { onScreen(Screen.VIEW) },
        )
        screen == Screen.SETTINGS -> {
            BackHandler { onScreen(Screen.STATUS) }
            SettingsScreen(state, runtime, resumeTick, settingsPage, onSettingsPage, onBack = { onScreen(Screen.STATUS) })
        }
        else -> StatusScreen(state, runtime, resumeTick, onOpenSettings = { onScreen(Screen.SETTINGS) })
    }
}

@Composable
private fun FullApp(
    runtime: NodeRuntime,
    resumeTick: Int,
    openRequest: OpenRequest?,
    tabs: List<Tab>,
    selected: Tab,
    onTab: (Tab) -> Unit,
    forwarding: @Composable () -> Unit,
    onOpenViewSettings: () -> Unit,
) {
    // A tab whose feature was just switched off falls back to the first one left.
    val tab = if (selected in tabs) selected else tabs.first()
    var platformThread by remember { mutableStateOf<RemoteConversation?>(null) }
    var platformTrash by rememberSaveable { mutableStateOf(false) }
    var threadId by rememberSaveable { mutableStateOf<Long?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }
    var trash by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openRequest) {
        openRequest ?: return@LaunchedEffect
        if (openRequest.platform != null) {
            if (Tab.PLATFORM in tabs) {
                onTab(Tab.PLATFORM)
                platformThread = openRequest.platform
            }
            return@LaunchedEffect
        }
        onTab(Tab.MESSAGES)
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

    if (tab == Tab.PLATFORM && platformTrash) {
        BackHandler { platformTrash = false }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) { PlatformRecycleBinScreen(runtime, onBack = { platformTrash = false }) }
        return
    }

    val remote = platformThread
    if (tab == Tab.PLATFORM && remote != null) {
        BackHandler { platformThread = null }
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            key(remote.key) { PlatformThreadScreen(runtime, remote, onBack = { platformThread = null }) }
        }
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
                Tab.PLATFORM -> PlatformScreen(
                    runtime,
                    onOpen = { platformThread = it },
                    onOpenSettings = {
                        onTab(Tab.FORWARD)
                        onOpenViewSettings()
                    },
                    onOpenTrash = { platformTrash = true },
                )
                Tab.FORWARD -> forwarding()
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            for (t in tabs) {
                val (icon, label) = when (t) {
                    Tab.MESSAGES -> Icons.Filled.Email to "短信"
                    Tab.PLATFORM -> Icons.Filled.Search to "平台"
                    Tab.FORWARD -> Icons.AutoMirrored.Filled.Send to "转发"
                }
                NavigationBarItem(selected = tab == t, onClick = { onTab(t) }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
            }
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
