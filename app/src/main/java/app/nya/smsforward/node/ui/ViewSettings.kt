package app.nya.smsforward.node.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.PairLink
import app.nya.smsforward.node.view.Scopes
import app.nya.smsforward.node.view.ViewConnection
import app.nya.smsforward.node.view.ViewResult
import app.nya.smsforward.node.view.ViewState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

/** The state of the 「查看」 entry in the settings list. */
fun viewSummary(state: ViewState): Pair<String, Tone?> = when {
    !state.enabled -> "关闭" to null
    state.connection == ViewConnection.NOT_CONNECTED -> "开启 · 还没有登录" to Tone.WARN
    state.connection == ViewConnection.NEEDS_LOGIN -> "开启 · 登录已失效" to Tone.BAD
    else -> "开启 · ${hostOf(state.serverUrl)}" to Tone.OK
}

private fun hostOf(url: String?): String = url?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty()

/**
 * The 「查看」 page: the switch, then logging in as a client of the platform (a pairing code of the 「客户端」 type, or
 * the admin password), or, once logged in, the identity and its permissions.
 */
@Composable
fun ViewSections(runtime: NodeRuntime) {
    val view = runtime.view
    val state by view.state.collectAsState()

    SectionCard("查看") {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "开启后多一个「平台」标签页：看平台上所有手机上报的短信，包括其他接收端的。" +
                    "需要用一个“客户端”身份登录，和这台手机的接收端身份分开，网页的设备列表里会多一条。",
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = state.enabled, onCheckedChange = { view.setEnabled(it) })
        }
        Text(
            "不需要新的权限（后台通知要通知权限）。能不能回复、新发、删除，看网页里给这个客户端勾了哪些权限。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!state.enabled) return

    when (state.connection) {
        ViewConnection.CONNECTED -> ViewAccountCard(runtime, state)
        ViewConnection.NEEDS_LOGIN, ViewConnection.NOT_CONNECTED -> ViewConnectCard(runtime, state)
    }
}

@Composable
private fun ViewAccountCard(runtime: NodeRuntime, state: ViewState) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    SectionCard("已登录") {
        Text(state.deviceName.orEmpty(), fontWeight = FontWeight.SemiBold)
        Text("服务器：${hostOf(state.serverUrl)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("权限：", style = MaterialTheme.typography.bodySmall)
            val all = listOf(Scopes.READ, Scopes.REPLY, Scopes.SEND, Scopes.DELETE)
            for (s in all) Pill(Scopes.label(s), if (s in state.scopes) Tone.OK else Tone.INFO)
        }
        if (Scopes.READ !in state.scopes) Warning("这个客户端没有“查看”权限，平台上的短信读不到。可以在网页的设备页里勾上。")
        BackgroundAlertsRow(runtime, state)
        if (state.ownInInbox) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("在「短信」里删除时，平台上的也删", fontWeight = FontWeight.Medium)
                    Text(
                        "本机短信在「短信」页删掉时，平台上对应的那条一起移到平台回收站（撤销时一起恢复）。需要“删除”权限。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.deleteOnPlatform, enabled = Scopes.DELETE in state.scopes, onCheckedChange = { runtime.view.setDeleteOnPlatform(it) })
            }
        }
        Text("令牌长期有效，加密保存在本机，和接收端的令牌分开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    note = when (val r = runtime.view.test()) {
                        is ViewResult.Ok -> true to r.message
                        is ViewResult.Error -> false to r.message
                    }
                    busy = false
                }
            }) { Text("测试连接") }
            TextButton(onClick = { confirm = true }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
        }
        note?.let { (ok, text) -> Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("退出查看登录？") },
            text = { Text("只清除本机保存的客户端令牌，接收端不受影响。网页设备列表里的这条客户端记录不会自动删除，不用了可以在那里删掉。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    runtime.view.disconnect()
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

/**
 * 「后台通知」: keeps the stream open behind the app (in the foreground service, one ongoing notification) so new
 * platform messages alert. Asks for the notification permission when switched on (Android 13+).
 */
@Composable
private fun BackgroundAlertsRow(runtime: NodeRuntime, state: ViewState) {
    val context = LocalContext.current
    var canNotify by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> canNotify = ok }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("后台通知", fontWeight = FontWeight.Medium)
            Text(
                "App 不在前台时也保持连接，平台上有新短信就弹通知（带“复制验证码”和“标为已读”）。会多一条常驻通知；" +
                    "本机设为默认短信应用时，本机自己的短信只由「短信」提醒一次。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.backgroundAlerts,
            onCheckedChange = { on ->
                runtime.view.setBackgroundAlerts(on)
                if (on && !canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) request.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
    if (state.backgroundAlerts && !canNotify) Warning("没有通知权限，收不到提醒。可以在系统设置里给本 App 打开通知。")
}

private enum class LoginMode { CODE, ACCOUNT }

@Composable
private fun ViewConnectCard(runtime: NodeRuntime, state: ViewState) {
    val view = runtime.view
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(LoginMode.CODE) }
    var url by rememberSaveable { mutableStateOf(state.serverUrl ?: view.suggestedServer().orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // never saved, not even across rotation
    var totp by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(state.deviceName.orEmpty()) }
    var errors by remember { mutableStateOf<Map<ViewResult.Field, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult // cancelled
        val link = PairLink.parse(text)
        if (link == null) {
            message = "这不是 NyaSmsForward 的配对二维码。请扫描 Web「设备与客户端」里生成的二维码。"
        } else {
            mode = LoginMode.CODE
            url = link.server
            code = link.code
            errors = emptyMap()
            message = null
        }
    }

    SectionCard("登录平台") {
        if (state.connection == ViewConnection.NEEDS_LOGIN) {
            Banner(Tone.BAD, Icons.Filled.Warning, "登录已失效（可能在网页里被删除或吊销），请重新登录。")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == LoginMode.CODE, onClick = { mode = LoginMode.CODE; message = null }, label = { Text("配对码") })
            FilterChip(selected = mode == LoginMode.ACCOUNT, onClick = { mode = LoginMode.ACCOUNT; message = null }, label = { Text("管理员账号") })
        }
        Text(
            if (mode == LoginMode.CODE) {
                "在 Web「设备与客户端」里选择“客户端”生成配对码（不是接收端的配对码），可以直接扫二维码。"
            } else {
                "用网页管理员的密码换一个长期令牌，密码只用这一次，不会保存。"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mode == LoginMode.CODE) {
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("对准 Web 里生成的客户端配对二维码")
                            .setBeepEnabled(false).setOrientationLocked(false),
                    )
                },
            ) { Text("扫描配对二维码") }
        }
        Field("服务器地址", url, errors[ViewResult.Field.URL], placeholder = "https://sms.example.com", keyboard = KeyboardType.Uri) {
            url = it; errors = errors - ViewResult.Field.URL
        }
        if (mode == LoginMode.CODE) {
            // Filter before limiting: the console shows "483 920", and pasting that must not be cut off at 6 characters.
            Field("配对码", code, errors[ViewResult.Field.CODE], placeholder = "6 位数字", keyboard = KeyboardType.Number) { v ->
                code = v.filter { Character.digit(it, 10) >= 0 }.take(6); errors = errors - ViewResult.Field.CODE
            }
        } else {
            Field("管理员密码", password, errors[ViewResult.Field.PASSWORD], keyboard = KeyboardType.Password, secret = true) {
                password = it; errors = errors - ViewResult.Field.PASSWORD
            }
            Field("动态验证码（开启了二次验证才需要）", totp, errors[ViewResult.Field.TOTP], keyboard = KeyboardType.Number) { v ->
                totp = v.filter { Character.digit(it, 10) >= 0 }.take(8); errors = errors - ViewResult.Field.TOTP
            }
        }
        Field("设备名称（网页设备列表里显示）", name, null, placeholder = "默认：手机型号（查看）") { name = it }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    val r = if (mode == LoginMode.CODE) view.connectWithCode(url, code, name) else view.login(url, password, totp, name)
                    busy = false
                    when (r) {
                        is ViewResult.Ok -> password = ""
                        is ViewResult.Error -> if (r.field != null) errors = errors + (r.field to r.message) else message = r.message
                    }
                }
            },
        ) { Text(if (busy) "登录中…" else "登录") }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Warning(text: String) {
    Banner(Tone.WARN, Icons.Filled.Warning, text)
}
