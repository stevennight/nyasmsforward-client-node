package app.nya.smsforward.node.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.nya.smsforward.node.node.ConnectionKind
import app.nya.smsforward.node.node.NodeRuntime
import kotlinx.coroutines.delay

private enum class Screen { STATUS, SETTINGS }

/** Picks the screen: pairing while not connected, otherwise the status page with the connection settings behind it. */
@Composable
fun NodeApp(runtime: NodeRuntime, resumeTick: Int) {
    val state by runtime.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.STATUS) }

    // While visible, keep the numbers fresh (a new SMS or an upload finishing changes them from other threads).
    LaunchedEffect(resumeTick) {
        while (true) {
            runtime.refresh()
            delay(3_000)
        }
    }

    when (state.connection) {
        ConnectionKind.PAIRED -> when (screen) {
            Screen.STATUS -> StatusScreen(state, runtime, resumeTick, onOpenSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> {
                BackHandler { screen = Screen.STATUS }
                SettingsScreen(state, runtime, onBack = { screen = Screen.STATUS })
            }
        }
        else -> PairScreen(state, runtime)
    }
}
