package app.nya.smsforward.node

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.ui.NodeApp
import app.nya.smsforward.node.ui.NyaTheme

class MainActivity : ComponentActivity() {
    // Bumped every time the app comes back to the foreground, so screens re-check permissions the user may have just
    // changed in the system settings.
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = NodeRuntime.get(this)
        setContent {
            NyaTheme {
                NodeApp(runtime, resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}
