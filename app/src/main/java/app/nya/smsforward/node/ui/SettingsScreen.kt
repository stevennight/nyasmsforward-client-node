package app.nya.smsforward.node.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.TestResult
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.service.NodeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connection settings: change the server address, test the connection, or disconnect. */
@Composable
fun SettingsScreen(state: UiState, runtime: NodeRuntime, resumeTick: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf(state.serverUrl.orEmpty()) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (ok?, text)
    var busy by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
            }
            Text("连接设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            SectionCard("服务器地址") {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; urlError = null; note = null },
                    isError = urlError != null,
                    supportingText = urlError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "换域名或端口不需要重新配对：令牌和地址无关。新地址只有在那台服务器认得这台手机时才会保存，输错了也不会把手机锁在外面。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && url.trim() != state.serverUrl,
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runtime.pairing.changeServerUrl(url) }
                                busy = false
                                when (r) {
                                    PairResult.Success -> { note = true to "已保存，令牌继续有效。"; runtime.refresh() }
                                    is PairResult.Error -> urlError = r.message
                                }
                            }
                        },
                    ) { Text("保存并验证") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runtime.pairing.testConnection() }
                                busy = false
                                note = when (r) {
                                    is TestResult.Ok -> true to r.message
                                    is TestResult.Error -> false to r.message
                                }
                                runtime.refresh()
                            }
                        },
                    ) { Text("测试连接") }
                }
                note?.let { (ok, text) ->
                    Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }

            SendSettingsCard(state, runtime, resumeTick)

            SentSyncCard(state, runtime, resumeTick)

            SectionCard("这台手机") {
                Text(state.deviceName.orEmpty(), fontWeight = FontWeight.SemiBold)
                Text("登录令牌：长期有效，不会自动过期，加密保存在本机。", style = MaterialTheme.typography.bodySmall)
                Text("待上报 ${state.pending} 条 · 上次上报 ${formatTime(state.lastUploadAt)}", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("断开连接") {
                Text(
                    "清除本机的令牌并停止接收。没上报完的短信会保留，下次配对后继续上报。要收短信的话不要断开。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmDisconnect = true }) { Text("断开并清除令牌", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("断开连接？") },
            text = {
                Text("这台手机将不再接收和上报短信，直到重新配对。本机还有 ${state.pending} 条未上报的短信，会保留到下次配对。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch {
                        withContext(Dispatchers.IO) { runtime.pairing.disconnect() }
                        NodeService.sync(context) // no token any more: the send channel stops
                        runtime.refresh()
                    }
                }) { Text("断开", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("取消") } },
        )
    }
}
