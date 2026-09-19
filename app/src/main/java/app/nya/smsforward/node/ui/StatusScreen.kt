package app.nya.smsforward.node.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nya.smsforward.node.data.OutboxState
import app.nya.smsforward.node.data.RecentItem
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.work.UploadScheduler

private data class Perm(val permission: String, val title: String, val hint: String, val required: Boolean)

private fun permissions(): List<Perm> = buildList {
    add(Perm(Manifest.permission.RECEIVE_SMS, "接收短信", "必需。没有它就收不到任何短信。", true))
    add(Perm(Manifest.permission.READ_PHONE_STATE, "读取 SIM 信息", "可选。显示卡 1 / 卡 2 的运营商名称。", false))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Perm(Manifest.permission.POST_NOTIFICATIONS, "通知", "可选。令牌失效需要重新配对时提醒你。", false))
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun StatusScreen(state: UiState, runtime: NodeRuntime, resumeTick: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    // resumeTick changes whenever the app returns to the foreground, e.g. from the system permission screen.
    var checked by remember(resumeTick) { mutableStateOf(permissions().associate { it.permission to granted(context, it.permission) }) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        checked = checked + result
    }
    val missing = permissions().filter { checked[it.permission] != true }
    val smsGranted = checked[Manifest.permission.RECEIVE_SMS] == true

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("NyaSmsForward 接收端", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            SectionCard("连接状态") {
                Text("已连接 ${state.serverUrl?.removePrefix("https://")?.removePrefix("http://").orEmpty()}", fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.deviceName.orEmpty()} · 登录令牌长期有效",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("待上报 ${state.pending} 条 · 上次上报 ${formatTime(state.lastUploadAt)}")
                state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { UploadScheduler.enqueue(context) }) { Text("立即同步") }
                    OutlinedButton(onClick = onOpenSettings) { Text("连接设置") }
                }
            }

            SectionCard("权限") {
                for (p in permissions()) {
                    val ok = checked[p.permission] == true
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.title, fontWeight = FontWeight.Medium)
                            Text(p.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (ok) "已授权" else if (p.required) "未授权" else "未开启",
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (missing.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { request.launch(missing.map { it.permission }.toTypedArray()) }) { Text("授予权限") }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text("应用设置") }
                    }
                }
                if (!smsGranted) {
                    Text(
                        "如果点“授予权限”没有弹窗，或系统提示“受限制的设置”：到「应用设置」右上角 ⋮ 选择“允许受限制的设置”，再回来授予。" +
                            "国产系统还可能需要在“权限管理”里单独允许“读取 / 接收短信”，以及“通知类短信”。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard("最近收到的短信") {
                if (state.recent.isEmpty()) {
                    Text("还没有短信。收到后会在这里显示，并自动上报。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.recent.forEach { RecentRow(it) }
            }
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.peer, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when (item.state) {
                    OutboxState.PENDING -> "待上报"
                    OutboxState.DONE -> "已上报"
                    OutboxState.DEAD -> "被拒绝"
                },
                color = when (item.state) {
                    OutboxState.DONE -> MaterialTheme.colorScheme.primary
                    OutboxState.DEAD -> MaterialTheme.colorScheme.error
                    OutboxState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(item.body, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.state == OutboxState.DEAD && item.error != null) {
            Text("原因：${item.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusPreview() {
    NyaTheme {
        val runtime = NodeRuntime.get(LocalContext.current)
        StatusScreen(
            UiState(serverUrl = "https://sms.example.com", deviceName = "Pixel 7", pending = 2, lastUploadAt = System.currentTimeMillis()),
            runtime, resumeTick = 0, onOpenSettings = {},
        )
    }
}
