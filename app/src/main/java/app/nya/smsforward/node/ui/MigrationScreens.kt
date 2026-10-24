package app.nya.smsforward.node.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.migrate.LegacyMigration
import app.nya.smsforward.node.migrate.LegacyStatus
import app.nya.smsforward.node.migrate.Migration
import app.nya.smsforward.node.migrate.MigrationResult
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.update.InstallResult
import app.nya.smsforward.node.update.NodeUpdater
import app.nya.smsforward.node.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun launchApp(context: Context, packageName: String): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

private fun installed(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (_: Exception) {
    false
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Standard app: the old full edition is installed. Offers to move its data and pairing here ([LegacyMigration]), then
 * walks through what is left: becoming the default SMS app and uninstalling the old app.
 */
@Composable
fun MigrationScreen(status: LegacyStatus, result: MigrationResult?, runtime: NodeRuntime, onMigrated: (MigrationResult) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    val isDefault = remember(recheck, result) { SmsStore.isDefaultSmsApp(context) }
    val legacyLeft = remember(recheck, result) { installed(context, Migration.LEGACY_PACKAGE) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck++ }
    val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck++ }
    fun close(dismiss: Boolean) {
        if (dismiss) LegacyMigration.dismiss(context)
        scope.launch { runtime.refresh() }
        onClose()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            NyaTopBar("从完整版迁移")
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    result != null -> {
                        SectionCard("迁移完成") {
                            Banner(Tone.OK, Icons.Filled.CheckCircle, if (result.movedPairing) "配对已搬过来，不用重新配对" else "数据已搬过来")
                            Hint(
                                "回收站 ${result.trash} 条、拦截记录 ${result.blocked} 条、拦截规则 ${result.rules} 条" +
                                    (if (result.movedPairing) "、待上报和已上报记录 ${result.queued} 条" else "") + "。",
                            )
                            if (result.keptOwnPairing) {
                                Hint("本 App 原来就配对过，保留了现在的配对。旧版在平台上的那台设备已经不用了，可以在网页的设备列表里删掉。")
                            }
                        }
                        SectionCard("还剩两步") {
                            if (isDefault) {
                                Banner(Tone.OK, Icons.Filled.CheckCircle, "1. 已是默认短信应用")
                            } else {
                                Text("1. 把本 App 设为默认短信应用", fontWeight = FontWeight.Medium)
                                Hint("设好之前，新短信还由旧版保存，不会丢。")
                                Button(onClick = { roleLauncher.launch(SmsStore.requestDefaultIntent(context)) }) { Text("设为默认短信应用") }
                            }
                            if (!legacyLeft) {
                                Banner(Tone.OK, Icons.Filled.CheckCircle, "2. 旧版已卸载")
                            } else {
                                Text("2. 卸载旧版「Nya 短信」", fontWeight = FontWeight.Medium)
                                Hint("在接下来的系统页面里点「卸载」。手机里的短信不会因此丢失。")
                                OutlinedButton(
                                    onClick = {
                                        uninstallLauncher.launch(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${Migration.LEGACY_PACKAGE}")),
                                        )
                                    },
                                ) { Text("卸载旧版") }
                                if (!isDefault) Hint("建议先完成第 1 步。先卸载也不会丢短信，系统会换回自带的短信应用。")
                            }
                        }
                        Button(onClick = { close(dismiss = false) }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                    }

                    status is LegacyStatus.NeedsUpdate -> {
                        SectionCard("发现旧版完整版 v${status.version}") {
                            Text("完整版已经合并进本 App。要把旧版的设置、配对、回收站和拦截记录搬过来，旧版要先更新到 v${BuildConfig.VERSION_NAME}。")
                            Hint("打开旧版 → 设置 → 关于与更新 → 检查更新，装好后回到这里。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { launchApp(context, Migration.LEGACY_PACKAGE) }) { Text("打开旧版") }
                                TextButton(onClick = { close(dismiss = true) }) { Text("不迁移") }
                            }
                        }
                    }

                    status is LegacyStatus.Ready -> {
                        val pairedHere = status.pairedHere
                        SectionCard("把旧版完整版的数据搬过来") {
                            Text("完整版已经合并进本 App。旧版 v${status.version} 里的东西可以一次搬过来：")
                            if (pairedHere) {
                                Hint("· 手机回收站、拦截规则和拦截记录\n本 App 已经配对过，会保留现在的配对；旧版那台设备可以之后在网页上删掉。")
                            } else {
                                Hint("· 配对（平台上还是同一台手机，不用重新配对）\n· 所有设置、待上报的短信\n· 手机回收站、拦截规则和拦截记录")
                            }
                            Hint("迁移后旧版不再上报。手机里的短信本来就在系统里，不需要搬。")
                            error?.let { Banner(Tone.BAD, Icons.Filled.Warning, it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { runCatching { LegacyMigration.run(context) } }
                                            busy = false
                                            r.onSuccess { onMigrated(it); runtime.refresh() }
                                                .onFailure { error = it.message ?: "迁移失败" }
                                        }
                                    },
                                ) { Text(if (busy) "正在迁移…" else "开始迁移") }
                                TextButton(enabled = !busy, onClick = { close(dismiss = true) }) { Text("不迁移") }
                            }
                        }
                    }

                    status is LegacyStatus.HandedOver -> {
                        SectionCard("旧版已经迁移过了") {
                            Text("旧版完整版已经把数据交出来了，现在可以卸载它。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { LegacyMigration.openLegacyAppInfo(context) }) { Text("卸载旧版") }
                                TextButton(onClick = { close(dismiss = true) }) { Text("完成") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Legacy app (the old full edition's last builds): a notice above everything saying it was merged, with the way to
 * the standard app. It keeps working normally until the standard app takes over.
 */
@Composable
fun LegacyNotice(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handedOver = remember(resumeTick) { Migration.handedOver(context) }
    val standardInstalled = remember(resumeTick) { installed(context, Migration.STANDARD_PACKAGE) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val (bg, fg) = toneColors(Tone.WARN)

    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(if (handedOver) "已迁移到新版「短信接收端」" else "完整版已合并到新版「短信接收端」", color = fg, fontWeight = FontWeight.Bold)
            Text(
                if (handedOver) {
                    "设置和配对已经交给新版，本 App 不再上报。请在新版里设为默认短信应用，然后卸载本 App。"
                } else {
                    "装上并打开新版，它会把这里的设置、配对、回收站和拦截记录自动搬过去，不用重新配对。迁移前本 App 照常工作。"
                },
                color = fg, style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (standardInstalled) {
                    Button(onClick = { launchApp(context, Migration.STANDARD_PACKAGE) }) { Text("打开新版") }
                } else {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            note = "正在下载新版…"
                            scope.launch {
                                note = withContext(Dispatchers.IO) {
                                    when (val r = NodeUpdater.check(BuildConfig.VERSION_NAME, NodeUpdater.STANDARD_APK_PREFIX, allowSameVersion = true)) {
                                        is UpdateCheck.Available -> when (val i = NodeUpdater.downloadAndInstall(context, r.update)) {
                                            InstallResult.Started -> null
                                            InstallResult.PermissionRequired -> "请允许本 App 安装应用，然后再点一次。"
                                            is InstallResult.Failed -> i.message
                                        }
                                        is UpdateCheck.Failed -> r.message
                                        else -> "GitHub Release 上还没有新版。"
                                    }
                                }
                                busy = false
                            }
                        },
                    ) { Text("下载新版") }
                }
                if (handedOver) {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text("卸载本应用") }
                }
            }
            note?.let { Text(it, color = fg, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
