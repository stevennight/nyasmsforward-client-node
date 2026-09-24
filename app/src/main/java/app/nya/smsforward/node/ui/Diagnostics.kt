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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.UiState
import app.nya.smsforward.node.update.InstallResult
import app.nya.smsforward.node.update.NodeUpdater
import app.nya.smsforward.node.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Perm(val permission: String, val title: String, val hint: String, val required: Boolean)

fun permissions(): List<Perm> = buildList {
    add(Perm(Manifest.permission.RECEIVE_SMS, "接收短信", "必需。没有它就收不到任何短信。", true))
    add(Perm(Manifest.permission.READ_PHONE_STATE, "读取 SIM 信息", "可选。显示卡 1 / 卡 2 的运营商名称。", false))
    add(Perm(Manifest.permission.READ_PHONE_NUMBERS, "读取本机号码", "可选。用于在换卡槽后仍按正确的号码发送。", false))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Perm(Manifest.permission.POST_NOTIFICATIONS, "通知", "可选。令牌失效需要重新配对时提醒你。", false))
    }
}

fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

@Composable
fun PermissionsCard(resumeTick: Int) {
    val context = LocalContext.current
    var checked by remember(resumeTick) { mutableStateOf(permissions().associate { it.permission to granted(context, it.permission) }) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checked = checked + it }
    val missing = permissions().filter { checked[it.permission] != true }

    SectionCard("权限", icon = Icons.Filled.Lock) {
        for (p in permissions()) {
            val ok = checked[p.permission] == true
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (ok) Icons.Filled.CheckCircle else if (p.required) Icons.Filled.Warning else Icons.Filled.Info,
                    contentDescription = null,
                    tint = if (ok) toneColors(Tone.OK).second else if (p.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(p.title, fontWeight = FontWeight.Medium)
                    Text(p.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (missing.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { request.launch(missing.map { it.permission }.toTypedArray()) }) { Text("授予权限") }
                OutlinedButton(onClick = { openAppSettings(context) }) { Text("应用设置") }
            }
            Text(
                "点“授予权限”没有弹窗，或提示“受限制的设置”时：到「应用设置」右上角 ⋮ 选择“允许受限制的设置”再回来。" +
                    "国产系统还可能要在“权限管理”里单独允许“读取 / 接收短信”和“通知类短信”。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateCheck>(UpdateCheck.Idle) }
    var installNote by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (update is UpdateCheck.Checking) return
        installNote = null
        update = UpdateCheck.Checking
        scope.launch { update = withContext(Dispatchers.IO) { NodeUpdater.check(BuildConfig.VERSION_NAME) } }
    }

    SectionCard("关于与更新", icon = Icons.Filled.Refresh) {
        Text(
            "当前版本 v${BuildConfig.VERSION_NAME} · ${if (BuildConfig.FULL_EDITION) "完整版" else "普通版"}",
            fontWeight = FontWeight.Medium,
        )
        when (val result = update) {
            UpdateCheck.Idle -> Text("从 GitHub Release 检查新版本，下载后会校验 SHA-256。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdateCheck.Checking -> Text("正在检查更新…", style = MaterialTheme.typography.bodySmall)
            is UpdateCheck.UpToDate -> Banner(Tone.OK, Icons.Filled.CheckCircle, "已是最新版本")
            is UpdateCheck.Failed -> Banner(Tone.BAD, Icons.Filled.Warning, result.message)
            is UpdateCheck.Available -> {
                Banner(Tone.INFO, Icons.Filled.Info, "发现新版本 v${result.update.version}")
                if (result.update.releaseNotes.isNotBlank()) {
                    Text(result.update.releaseNotes, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    enabled = installNote == null,
                    onClick = {
                        installNote = "正在下载并校验 APK…"
                        scope.launch {
                            val install = withContext(Dispatchers.IO) { NodeUpdater.downloadAndInstall(context, result.update) }
                            installNote = when (install) {
                                InstallResult.Started -> "APK 已校验，已打开系统安装确认。"
                                InstallResult.PermissionRequired -> "请允许本应用安装未知来源应用，然后再次点击安装。"
                                is InstallResult.Failed -> install.message
                            }
                        }
                    },
                ) { Text("下载并安装") }
            }
        }
        installNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("失败") || it.contains("允许")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (update !is UpdateCheck.Checking) OutlinedButton(onClick = { check() }) { Text("检查更新") }
    }
}

@Composable
fun SimCard(state: UiState, runtime: NodeRuntime) {
    SectionCard("SIM 卡", icon = Icons.Filled.Phone) {
        if (state.sims.isEmpty()) {
            Text(
                "系统没有向本应用提供 SIM 信息。请在上面授予“读取 SIM 信息”，返回此页会自动刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.sims.forEach { sim -> SimRow(sim, state.manualSimNumbers[sim.slot].orEmpty(), runtime) }
        }
    }
}

private fun maskPhoneNumber(number: String): String =
    if (number.length <= 7) number else number.take(3) + "****" + number.takeLast(4)

@Composable
private fun SimRow(sim: SimInfo, manualNumber: String, runtime: NodeRuntime) {
    var draft by remember(sim.slot, manualNumber) { mutableStateOf(manualNumber) }
    var message by remember(sim.slot) { mutableStateOf<String?>(null) }
    var editing by remember(sim.slot) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("卡 ${sim.slot}")
            Text(sim.label ?: "运营商未知", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                sim.number?.let(::maskPhoneNumber) ?: "号码未知",
                style = MaterialTheme.typography.bodySmall,
                color = if (sim.number == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "收起" else "设置号码") }
        }
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; message = null },
                label = { Text("卡 ${sim.slot} 的号码") },
                placeholder = { Text("例如 13800138000") },
                supportingText = { Text("系统读不到本机号码时手动填写，平台按号码识别是哪张卡。") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { message = runtime.setManualSimNumber(sim.slot, draft) ?: "已保存，下次连接时上报" }) { Text("保存") }
                if (manualNumber.isNotEmpty()) {
                    OutlinedButton(onClick = { draft = ""; message = runtime.setManualSimNumber(sim.slot, "") ?: "已清除" }) { Text("清除") }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
