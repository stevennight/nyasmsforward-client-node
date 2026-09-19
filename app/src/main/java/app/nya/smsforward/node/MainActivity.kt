package app.nya.smsforward.node

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nya.smsforward.node.ui.NyaTheme
import app.nya.smsforward.node.ui.StatusScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NyaTheme {
                StatusScreen(versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}
