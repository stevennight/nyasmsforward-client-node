package app.nya.smsforward.node.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nya.smsforward.node.net.ChannelState
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.policy.SendPolicy

private val LIMITS = listOf(5, 10, 20, 50)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** What the phone will do when the platform asks it to send an SMS. Off by default; only changeable here. */
@Composable
fun SendSettingsCard(state: UiState, runtime: NodeRuntime, resumeTick: Int) {
    val context = LocalContext.current
    var canSend by remember(resumeTick) { mutableStateOf(granted(context, Manifest.permission.SEND_SMS)) }
    var canReadSims by remember(resumeTick) { mutableStateOf(granted(context, Manifest.permission.READ_PHONE_STATE)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        canSend = result[Manifest.permission.SEND_SMS] ?: canSend
        canReadSims = result[Manifest.permission.READ_PHONE_STATE] ?: canReadSims
    }

    fun choose(policy: SendPolicy) {
        runtime.applySendPolicy(policy)
        if (policy != SendPolicy.OFF) {
            val missing = buildList {
                if (!canSend) add(Manifest.permission.SEND_SMS)
                if (!canReadSims) add(Manifest.permission.READ_PHONE_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
                    add(Manifest.permission.POST_NOTIFICATIONS) // the service notification is only visible with it
                }
            }
            if (missing.isNotEmpty()) request.launch(missing.toTypedArray())
        }
    }

    SectionCard("下发：让平台经这台手机发短信") {
        Text(
            "默认关闭。这个设置只能在这台手机上改，服务器无法绕过：即使服务器被攻破，也只能在你允许的范围内发短信。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PolicyOption(SendPolicy.OFF, "关闭", "拒绝所有下发任务，不保持后台连接。", state.sendPolicy, ::choose)
            PolicyOption(SendPolicy.REPLY, "仅回复", "只回复最近 7 天内给这台手机发过短信的号码，从收到短信的那张卡发出。", state.sendPolicy, ::choose)
            PolicyOption(SendPolicy.ANY, "允许新发", "除回复外，还可以向任意号码发短信。风险最大，请只在需要时开启。", state.sendPolicy, ::choose)
        }

        if (state.sendPolicy != SendPolicy.OFF) {
            Text("每小时最多发送", fontWeight = FontWeight.Medium)
            ChoiceRow {
                for (n in LIMITS) {
                    if (n == state.sendLimitPerHour) {
                        Button(onClick = {}) { ChoiceLabel("$n") }
                    } else {
                        OutlinedButton(onClick = { runtime.applySendLimit(n) }) { ChoiceLabel("$n") }
                    }
                }
            }
            Text(
                "计数在手机上，服务器改不了。收到 106 等服务号的回复指令（如 TD）只算 1 条。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!canSend) Warning("没有“发送短信”权限，下发任务都会失败（no_permission）。")
            if (!canReadSims) Warning("没有“读取 SIM 信息”权限，无法按卡槽选择用哪张卡发送，指定了卡槽的任务会失败。")
            if (!canSend || !canReadSims) {
                OutlinedButton(onClick = {
                    request.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE))
                }) { Text("授予权限") }
            }
            BatteryHint(resumeTick)
        }
    }
}

@Composable
private fun PolicyOption(policy: SendPolicy, title: String, hint: String, current: SendPolicy, onChoose: (SendPolicy) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = current == policy, role = Role.RadioButton, onClick = { onChoose(policy) }),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = current == policy, onClick = null)
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** A row of choices that wraps whole buttons onto the next line instead of squeezing a label into several lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ChoiceLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false)
}

/**
 * The system may stop a background connection to save power; exempting the app keeps the channel reliable. The state is
 * read again whenever the app comes back to the foreground (the user answers the system dialog outside of it), and it
 * stays visible once granted instead of disappearing.
 */
@Composable
private fun BatteryHint(resumeTick: Int) {
    val context = LocalContext.current
    val exempt = remember(resumeTick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    if (exempt) {
        Text("已允许本应用不受电池优化限制 ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        return
    }
    Text(
        "为了让下发通道不被系统杀掉，建议把本应用加入“不受电池优化限制”（部分国产系统还需要允许自启动和后台运行）。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = {
        // The direct "allow" dialog when the system offers it, otherwise the list where it can be switched by hand.
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }) { Text("允许不受电池优化限制") }
}

/** One line for the status screen: is sending on, and is the channel up. */
@Composable
fun SendStatusCard(state: UiState, onOpenSettings: () -> Unit) {
    SectionCard("下发") {
        val policy = when (state.sendPolicy) {
            SendPolicy.OFF -> "关闭"
            SendPolicy.REPLY -> "仅回复"
            SendPolicy.ANY -> "允许新发"
        }
        Text("策略：$policy", fontWeight = FontWeight.SemiBold)
        if (state.sendPolicy == SendPolicy.OFF) {
            Text("平台无法让这台手机发短信。需要在网页 / 客户端里回复短信时，到设置里开启。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val (text, bad) = when (val c = state.channel) {
                ChannelState.Connected -> "下发通道已连接" to false
                ChannelState.Connecting -> "正在连接下发通道…" to false
                ChannelState.Idle -> "下发通道未运行" to true
                ChannelState.NeedsPairing -> "令牌已失效，需要重新配对" to true
                is ChannelState.Waiting -> "${c.reason}，${c.retryInMs / 1000} 秒后重试" to true
            }
            Text(text, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            state.sendTasks.forEach { t ->
                Text(
                    "${formatTime(t.updatedAt)} · ${t.recipient} · ${when (t.state) {
                        app.nya.smsforward.node.data.LedgerState.SENDING -> "发送中"
                        app.nya.smsforward.node.data.LedgerState.SENT -> "已发送"
                        app.nya.smsforward.node.data.LedgerState.DELIVERED -> "已送达"
                        app.nya.smsforward.node.data.LedgerState.FAILED -> "失败${t.error?.let { "（$it）" }.orEmpty()}"
                    }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(onClick = onOpenSettings) { Text("下发设置") }
    }
}

private val HISTORY_CHOICES = listOf(0 to "不回补", 7 to "最近 7 天", 30 to "最近 30 天")

/**
 * Optional: report what the user sends from the phone's own SMS app, and read old history once. Off by default, and
 * READ_SMS is only asked for at the moment it is switched on.
 */
@Composable
fun SentSyncCard(state: UiState, runtime: NodeRuntime, resumeTick: Int) {
    val context = LocalContext.current
    var canRead by remember(resumeTick) { mutableStateOf(granted(context, Manifest.permission.READ_SMS)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        canRead = ok
        // Without the permission there is nothing to read: keep the switch off instead of pretending.
        if (!ok) runtime.applySyncSent(false)
    }
    // From the observed state, not read from the settings in place: a plain read is not re-composed after a tap.
    val on = state.syncSent
    val days = state.backfillDays

    SectionCard("同步手机上发出的短信（可选）") {
        Text(
            "在这台手机自带短信 App 里手动发出的短信，默认不会出现在平台上。开启后读取手机的短信数据库把它们补上，" +
                "会话就完整了（标注“手机上发出”，不弹通知、自动已读）。需要“读取短信”权限，只在你开启时才申请。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("同步发出的短信", fontWeight = FontWeight.Medium)
            androidx.compose.material3.Switch(
                checked = on && canRead,
                onCheckedChange = { want ->
                    if (want) {
                        runtime.applySyncSent(true)
                        if (!canRead) request.launch(Manifest.permission.READ_SMS)
                    } else {
                        runtime.applySyncSent(false)
                    }
                },
            )
        }
        if (on) {
            Text("回补历史（只做一次，标为已读、不弹通知）", fontWeight = FontWeight.Medium)
            ChoiceRow {
                for ((n, label) in HISTORY_CHOICES) {
                    if (n == days) {
                        Button(onClick = {}) { ChoiceLabel(label) }
                    } else {
                        OutlinedButton(onClick = { runtime.applyBackfillDays(n) }) { ChoiceLabel(label) }
                    }
                }
            }
            Text(
                "经平台发出的短信不会重复上报。手机进程被系统结束时，最多 15 分钟内补上；开着本 App 时几秒内就会同步。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!canRead) Warning("没有“读取短信”权限，无法同步。")
        }
    }
}
