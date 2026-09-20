package app.nya.smsforward.node.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.node.ConnectionKind
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.PairLink
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.withContext

/**
 * Shown while the phone is not connected to a server: first run, after "disconnect", or after the server revoked the
 * token. The receiver pairs with a one-time code only; it never asks for the admin password.
 */
@Composable
fun PairScreen(state: UiState, runtime: NodeRuntime) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(state.serverUrl.orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(state.deviceName ?: Build.MODEL.orEmpty()) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // The console shows the pairing link as a QR code: scanning fills in the address and the code. The camera permission
    // is asked by the scanner screen itself, only when this button is used.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult // cancelled
        val link = PairLink.parse(text)
        if (link == null) {
            message = "这不是 NyaSmsForward 的配对二维码。请扫描 Web「设备与客户端」里生成的二维码。"
        } else {
            url = link.server
            code = link.code
            urlError = null
            codeError = null
            message = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("连接到服务器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "在 Web 管理台「设备与客户端」里选择“接收端手机”，生成配对码，然后在这里输入服务器地址和配对码。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.connection == ConnectionKind.NEEDS_PAIRING) {
                SectionCard("需要重新配对") {
                    Text("令牌已被吊销或失效。短信仍在继续接收并保存在本机，重新配对后会自动补传，不会丢。", color = MaterialTheme.colorScheme.error)
                    if (state.pending > 0) Text("本机有 ${state.pending} 条短信等待上报。")
                }
            }

            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("对准 Web 里生成的配对二维码")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
            ) { Text("扫描配对二维码") }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it; urlError = null },
                label = { Text("服务器地址") },
                placeholder = { Text("https://sms.example.com") },
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                // Filter before limiting: the console shows "483 920", and pasting that must not be cut off at 6 characters.
                onValueChange = { v -> code = v.filter { Character.digit(it, 10) >= 0 }.take(6); codeError = null },
                label = { Text("配对码") },
                placeholder = { Text("6 位数字") },
                isError = codeError != null,
                supportingText = codeError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runtime.pairing.pair(url, code, name) }
                        busy = false
                        when (result) {
                            PairResult.Success -> runtime.refresh()
                            is PairResult.Error -> when (result.field) {
                                PairResult.Field.URL -> urlError = result.message
                                PairResult.Field.CODE -> codeError = result.message
                                null -> message = result.message
                            }
                        }
                    }
                },
            ) { Text(if (busy) "连接中…" else "连接") }

            Text(
                "只需要连接一次：令牌长期有效，重启手机、断网、服务器重启都不用重新配对。只有你在 Web 里吊销它才会失效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
