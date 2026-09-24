package app.nya.smsforward.node.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.data.OutboxState
import app.nya.smsforward.node.data.RecentItem
import app.nya.smsforward.node.net.ChannelState
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.policy.SendPolicy
import app.nya.smsforward.node.work.UploadScheduler

/**
 * The forwarding home: is this phone reporting (one big card), what needs fixing (only when something does), and what
 * arrived recently. Permissions, SIM details and updates live in the settings.
 */
@Composable
fun StatusScreen(state: UiState, runtime: NodeRuntime, resumeTick: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var smsGranted by remember(resumeTick) { mutableStateOf(granted(context, Manifest.permission.RECEIVE_SMS)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        smsGranted = result[Manifest.permission.RECEIVE_SMS] ?: smsGranted
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
            NyaTopBar("短信转发") {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusHero(state, smsGranted, onSync = { UploadScheduler.enqueue(context) })

                if (!smsGranted) {
                    Banner(Tone.BAD, Icons.Filled.Warning, "没有“接收短信”权限，收不到任何短信。") {
                        TextButton(onClick = { request.launch(permissions().map { it.permission }.toTypedArray()) }) { Text("授予") }
                    }
                }
                state.lastError?.let { Banner(Tone.WARN, Icons.Filled.Warning, it) }

                SendSummary(state, onOpenSettings)

                SectionCard("最近收到", icon = Icons.Filled.Email) {
                    if (state.recent.isEmpty()) {
                        Text("还没有短信。收到后会显示在这里，并自动上报。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.recent.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        RecentRow(item)
                    }
                }
            }
        }
    }
}

/** The one card that answers "is it working?". */
@Composable
private fun StatusHero(state: UiState, smsGranted: Boolean, onSync: () -> Unit) {
    val healthy = smsGranted && state.lastError == null
    val host = state.serverUrl?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty()
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(Color.White.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (healthy) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (healthy) "正在转发" else "需要处理",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        listOf(state.deviceName.orEmpty(), host).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color.White.copy(alpha = .8f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat("待上报", "${state.pending} 条", Modifier.weight(1f))
                HeroStat("上次上报", formatTime(state.lastUploadAt), Modifier.weight(1f))
            }
            FilledTonalButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  立即同步")
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Color.White.copy(alpha = .14f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Sending in one line; the details and the switch are in the settings. */
@Composable
private fun SendSummary(state: UiState, onOpenSettings: () -> Unit) {
    val policy = when (state.sendPolicy) {
        SendPolicy.OFF -> "关闭"
        SendPolicy.REPLY -> "仅回复"
        SendPolicy.ANY -> "允许新发"
    }
    val (channel, tone) = when {
        state.sendPolicy == SendPolicy.OFF -> "平台不能让这台手机发短信" to Tone.INFO
        else -> when (val c = state.channel) {
            ChannelState.Connected -> "下发通道已连接" to Tone.OK
            ChannelState.Connecting -> "正在连接下发通道…" to Tone.INFO
            ChannelState.Idle -> "下发通道未运行" to Tone.WARN
            ChannelState.NeedsPairing -> "令牌已失效，需要重新配对" to Tone.BAD
            is ChannelState.Waiting -> "${c.reason}，${c.retryInMs / 1000} 秒后重试" to Tone.WARN
        }
    }
    SectionCard("代发短信", icon = Icons.AutoMirrored.Filled.Send) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(policy, if (state.sendPolicy == SendPolicy.OFF) Tone.INFO else Tone.OK)
            Text(channel, style = MaterialTheme.typography.bodyMedium, color = toneColors(tone).second, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
        state.sendTasks.take(3).forEach { t ->
            Text(
                "${formatTime(t.updatedAt)} · ${t.recipient} · ${when (t.state) {
                    app.nya.smsforward.node.data.LedgerState.SENDING -> "发送中"
                    app.nya.smsforward.node.data.LedgerState.SENT -> "已发送"
                    app.nya.smsforward.node.data.LedgerState.DELIVERED -> "已送达"
                    app.nya.smsforward.node.data.LedgerState.FAILED -> "失败${t.error?.let { "（$it）" }.orEmpty()}"
                }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.peer, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.body, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.state == OutboxState.DEAD && item.error != null) {
                Text("原因：${item.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        when (item.state) {
            OutboxState.PENDING -> Pill("待上报", Tone.WARN)
            OutboxState.DONE -> Pill("已上报", Tone.OK)
            OutboxState.DEAD -> Pill("被拒绝", Tone.BAD)
        }
    }
}
