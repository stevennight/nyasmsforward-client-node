package app.nya.smsforward.node.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.inbox.SmsAppFeature
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.UiState
import kotlinx.coroutines.launch

/** "v0.6.0 · 短信应用已开启", the line under "关于与更新". */
fun versionLabel(smsApp: Boolean): String = "v${BuildConfig.VERSION_NAME} · " + when {
    BuildConfig.LEGACY_FULL -> "旧版完整版"
    smsApp -> "短信应用已开启"
    else -> "短信应用未开启"
}

/** The state of the "短信应用" entry in the settings list. */
fun smsAppSummary(state: UiState): Pair<String, Tone?> = when {
    state.smsAppTurningOff -> "等待切回系统短信后关闭" to Tone.WARN
    !state.smsApp -> "关闭" to null
    state.isDefaultSmsApp -> "开启 · 已是默认短信应用" to Tone.OK
    else -> "开启 · 还不是默认短信应用" to Tone.WARN
}

/** Opens the system page where the default SMS app is picked; the general settings on ROMs that lack it. */
fun openDefaultAppsSettings(context: Context) {
    val intents = listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
            // try the next one
        }
    }
}

/**
 * The "短信应用" page. Switching on enables the components and asks Android for the default-SMS-app role right away;
 * switching off while still the default SMS app sends the user to pick another one first (SmsAppFeature).
 */
@Composable
fun SmsAppSections(state: UiState, runtime: NodeRuntime) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askSwitchDefault by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch { runtime.refresh() }
    }

    SectionCard("短信应用") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "开启后可以把本 App 设为默认短信应用：在「短信」标签页收发短信，识别验证码、银行和快递短信，拦截骚扰短信；" +
                    "平台上的「同时删除手机短信」也只有这样才能真正删掉。",
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = state.smsApp && !state.smsAppTurningOff,
                onCheckedChange = { want ->
                    if (want) {
                        runtime.applySmsApp(true)
                        if (!SmsStore.isDefaultSmsApp(context)) roleLauncher.launch(SmsStore.requestDefaultIntent(context))
                    } else if (runtime.applySmsApp(false) == SmsAppFeature.OffResult.SWITCH_DEFAULT_FIRST) {
                        askSwitchDefault = true
                    }
                },
            )
        }
        Text(
            "关闭时本 App 不会出现在系统的短信应用列表里，也不会申请短信应用的权限。设为默认后，短信和彩信权限由系统自动授予；" +
                "显示联系人姓名的权限可选，在「短信」页面单独申请。转发、代发不受这个开关影响。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state.smsAppTurningOff -> Banner(Tone.WARN, Icons.Filled.Warning, "请在系统设置里把默认短信应用改回系统自带的短信，改好后这里会自动关闭。")
            state.smsApp && state.isDefaultSmsApp -> Banner(Tone.OK, Icons.Filled.CheckCircle, "已是默认短信应用")
            state.smsApp -> Banner(Tone.WARN, Icons.Filled.Warning, "还不是默认短信应用：现在只能查看短信，不能发送和删除。")
        }
        when {
            state.smsAppTurningOff -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openDefaultAppsSettings(context) }) { Text("去设置") }
                OutlinedButton(onClick = { runtime.cancelSmsAppTurnOff() }) { Text("不关了") }
            }
            state.smsApp && !state.isDefaultSmsApp -> Button(onClick = { roleLauncher.launch(SmsStore.requestDefaultIntent(context)) }) {
                Text("设为默认短信应用")
            }
        }
    }

    if (state.smsApp) {
        SectionCard("发送") {
            var reports by remember { mutableStateOf(SmsAppFeature.deliveryReports(context)) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("送达报告")
                    Text(
                        "在「短信」里发出的短信，请运营商回报对方有没有收到，会话里显示“已送达”或“未送达”。少数运营商不回报，那就一直只显示发送结果。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = reports, onCheckedChange = {
                    reports = it
                    SmsAppFeature.setDeliveryReports(context, it)
                })
            }
            Text("发送失败的短信点一下就能重新发送；没发出去的内容会存成草稿，下次打开会话还在。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (askSwitchDefault) {
        AlertDialog(
            onDismissRequest = {
                askSwitchDefault = false
                runtime.cancelSmsAppTurnOff()
            },
            title = { Text("先换回系统短信") },
            text = {
                Text(
                    "本 App 现在是默认短信应用。直接关闭的话，新来的短信就没有应用保存了。" +
                        "请在接下来的系统页面里把默认短信应用改回系统自带的短信，回到这里后会自动关闭。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askSwitchDefault = false
                    openDefaultAppsSettings(context)
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askSwitchDefault = false
                    runtime.cancelSmsAppTurnOff()
                }) { Text("取消") }
            },
        )
    }
}
