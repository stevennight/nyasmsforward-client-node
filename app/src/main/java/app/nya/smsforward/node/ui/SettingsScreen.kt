package app.nya.smsforward.node.ui

import android.os.PowerManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.net.ChannelState
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.PairResult
import app.nya.smsforward.node.node.TestResult
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.policy.SendPolicy
import app.nya.smsforward.node.service.NodeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsPage(val title: String) {
    SERVER("服务器与连接"),
    SEND("代发短信"),
    SYNC("同步发出的短信"),
    PERMISSIONS("权限与后台运行"),
    SIM("SIM 卡"),
    ABOUT("关于与更新"),
}

/**
 * Settings as a list of entries, each showing its current state, that open a page of their own; the way Android's own
 * settings are laid out. Only the entry list scrolls here; a page holds the details of one topic.
 */
@Composable
fun SettingsScreen(state: UiState, runtime: NodeRuntime, resumeTick: Int, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val open = page
    if (open != null) {
        BackHandler { page = null }
        SettingsPageFrame(open.title, onBack = { page = null }) {
            when (open) {
                SettingsPage.SERVER -> ServerSections(state, runtime)
                SettingsPage.SEND -> SendSettingsSections(state, runtime, resumeTick)
                SettingsPage.SYNC -> SentSyncSections(state, runtime, resumeTick)
                SettingsPage.PERMISSIONS -> {
                    PermissionsCard(resumeTick)
                    SectionCard("后台运行") { BatteryHint(resumeTick) }
                }
                SettingsPage.SIM -> SimCard(state, runtime)
                SettingsPage.ABOUT -> UpdateCard()
            }
        }
        return
    }
    SettingsHome(state, runtime, resumeTick, onBack, onOpen = { page = it })
}

@Composable
private fun SettingsPageFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
            NyaTopBar(title, onBack = onBack)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun SettingsHome(state: UiState, runtime: NodeRuntime, resumeTick: Int, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val missing = remember(resumeTick) { permissions().filter { !granted(context, it.permission) } }
    val batteryOk = remember(resumeTick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
            NyaTopBar("设置", onBack = onBack)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DeviceHeader(state, onClick = { onOpen(SettingsPage.SERVER) })

                SettingsGroup("转发") {
                    SettingsRow(Icons.Filled.Home, "服务器与连接", hostOf(state.serverUrl).ifBlank { "未设置" }) { onOpen(SettingsPage.SERVER) }
                    val (sendText, sendTone) = sendSummary(state)
                    SettingsRow(Icons.AutoMirrored.Filled.Send, "代发短信", sendText, subtitleTone = sendTone) { onOpen(SettingsPage.SEND) }
                    SettingsRow(
                        Icons.Filled.Refresh,
                        "同步发出的短信",
                        if (!state.syncSent) "关闭" else "开启 · " + when (state.backfillDays) { 0 -> "不回补历史"; else -> "回补最近 ${state.backfillDays} 天" },
                        divider = false,
                    ) { onOpen(SettingsPage.SYNC) }
                }

                SettingsGroup("这台手机") {
                    val required = missing.any { it.required }
                    SettingsRow(
                        Icons.Filled.Lock,
                        "权限与后台运行",
                        when {
                            required -> "缺少：" + missing.filter { it.required }.joinToString("、") { it.title }
                            missing.isNotEmpty() -> "${missing.size} 项可选权限未授予"
                            !batteryOk -> "建议允许不受电池优化限制"
                            else -> "全部就绪"
                        },
                        subtitleTone = when {
                            required -> Tone.BAD
                            missing.isNotEmpty() || !batteryOk -> Tone.WARN
                            else -> Tone.OK
                        },
                    ) { onOpen(SettingsPage.PERMISSIONS) }
                    SettingsRow(
                        Icons.Filled.Phone,
                        "SIM 卡",
                        if (state.sims.isEmpty()) "读不到 SIM 信息" else state.sims.joinToString(" · ") { "卡${it.slot} ${it.label ?: "未知"}" },
                        divider = false,
                    ) { onOpen(SettingsPage.SIM) }
                }

                SettingsGroup("其他") {
                    SettingsRow(
                        Icons.Filled.Info,
                        "关于与更新",
                        "v${BuildConfig.VERSION_NAME} · ${if (BuildConfig.FULL_EDITION) "完整版" else "普通版"}",
                    ) { onOpen(SettingsPage.ABOUT) }
                    SettingsRow(
                        Icons.AutoMirrored.Filled.ExitToApp,
                        "断开连接",
                        "清除令牌并停止接收",
                        danger = true,
                        divider = false,
                    ) { confirmDisconnect = true }
                }
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

/** Who this phone is and where it reports to, at the top of the settings. */
@Composable
private fun DeviceHeader(state: UiState, onClick: () -> Unit) {
    SettingsGroup(null) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(state.deviceName.orEmpty().ifBlank { "这台手机" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "待上报 ${state.pending} 条 · 上次上报 ${formatTime(state.lastUploadAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) { Text("连接") }
        }
    }
}

private fun hostOf(url: String?): String = url?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty()

private fun sendSummary(state: UiState): Pair<String, Tone?> {
    val policy = when (state.sendPolicy) {
        SendPolicy.OFF -> return "关闭" to null
        SendPolicy.REPLY -> "仅回复"
        SendPolicy.ANY -> "允许新发"
    }
    val limit = "每小时 ${state.sendLimitPerHour} 条"
    return when (state.channel) {
        ChannelState.Connected -> "$policy · $limit · 已连接" to Tone.OK
        ChannelState.Connecting -> "$policy · $limit · 连接中" to null
        ChannelState.NeedsPairing -> "$policy · 令牌已失效" to Tone.BAD
        else -> "$policy · $limit · 通道未连接" to Tone.WARN
    }
}

/** The "服务器与连接" page: change the address, test the connection, and what this phone is. */
@Composable
private fun ServerSections(state: UiState, runtime: NodeRuntime) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(state.serverUrl.orEmpty()) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (ok?, text)
    var busy by remember { mutableStateOf(false) }

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

    SectionCard("这台手机") {
        Text(state.deviceName.orEmpty(), fontWeight = FontWeight.SemiBold)
        Text("登录令牌：长期有效，不会自动过期，加密保存在本机。", style = MaterialTheme.typography.bodySmall)
        Text("待上报 ${state.pending} 条 · 上次上报 ${formatTime(state.lastUploadAt)}", style = MaterialTheme.typography.bodySmall)
    }
}
