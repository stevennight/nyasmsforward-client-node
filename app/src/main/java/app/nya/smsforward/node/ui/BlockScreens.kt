package app.nya.smsforward.node.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.data.BlockedSms
import app.nya.smsforward.node.data.SmsTrash
import app.nya.smsforward.node.inbox.ContactNames
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.sms.BlockRule
import app.nya.smsforward.node.sms.SpamCategory
import app.nya.smsforward.node.sms.SpamFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "骚扰拦截": what was held back (kept 30 days, can be moved to the inbox) and the rules that decide it. Intercepted SMS
 * are still reported to the platform; interception only keeps them out of the phone's inbox and notifications.
 */
@Composable
fun BlockedScreen(runtime: NodeRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val blocker = runtime.blocker
    var showRules by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val messages by produceState(emptyList<BlockedSms>(), reload) { value = withContext(Dispatchers.IO) { blocker.list() } }
    val rules by produceState(emptyList<BlockRule>(), reload) { value = withContext(Dispatchers.IO) { blocker.list.rules() } }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty() && !showRules
    var confirmClear by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    BackHandler(enabled = selecting) { selected = emptySet() }

    fun act(block: () -> Boolean, done: String?, failed: String = "操作失败") {
        scope.launch(Dispatchers.IO) {
            val ok = block()
            withContext(Dispatchers.Main) {
                if (!ok) toast(context, failed) else if (done != null) toast(context, done)
                reload++
            }
        }
    }

    /** Deletes intercepted messages, with "撤销" putting the same messages back. */
    suspend fun delete(targets: List<BlockedSms>): Boolean {
        withContext(Dispatchers.IO) { targets.forEach { blocker.list.remove(it.id) } }
        reload++
        scope.launch {
            val label = if (targets.size == 1) "已删除" else "已删除 ${targets.size} 条"
            if (snackbar.showSnackbar(label, actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { targets.forEach { blocker.list.add(it.copy(id = 0)) } }
                reload++
            }
        }
        return true
    }

    suspend fun moveToInbox(targets: List<BlockedSms>): Boolean {
        val moved = withContext(Dispatchers.IO) { targets.count { blocker.moveToInbox(it.id) } }
        toast(context, if (moved == targets.size) "已放回收件箱" else if (moved > 0) "已放回 $moved 条，其余失败" else "失败：需要仍是默认短信应用")
        reload++
        return moved > 0
    }

    Box(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                val picked = messages.filter { it.id in selected }
                SelectionTopBar(selected.size, onClose = { selected = emptySet() }) {
                    var more by remember { mutableStateOf(false) }
                    TextButton(onClick = {
                        selected = if (selected.size == messages.size) emptySet() else messages.mapTo(HashSet()) { it.id }
                    }) { Text(if (selected.size == messages.size) "全不选" else "全选") }
                    IconButton(onClick = {
                        selected = emptySet()
                        scope.launch { moveToInbox(picked) }
                    }) { Icon(Icons.Filled.Done, contentDescription = "放回收件箱") }
                    IconButton(onClick = {
                        selected = emptySet()
                        scope.launch { delete(picked) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("信任这些号码") }, onClick = {
                                more = false
                                selected = emptySet()
                                val numbers = picked.map { it.address }.filter { it.isNotBlank() }.distinct()
                                act({ numbers.sumOf { blocker.trustNumber(it) }; true }, "已信任 ${numbers.size} 个号码，它们的短信已放回收件箱")
                            })
                        }
                    }
                }
            } else {
                NyaTopBar("骚扰拦截", onBack = onBack) {
                    if (!showRules && messages.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            PrimaryTabRow(selectedTabIndex = if (showRules) 1 else 0, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = !showRules, onClick = { showRules = false }, text = { Text(if (messages.isEmpty()) "拦截记录" else "拦截记录（${messages.size}）") })
                Tab(selected = showRules, onClick = { showRules = true; selected = emptySet() }, text = { Text("拦截规则") })
            }
            if (showRules) {
                RulesPane(
                    rules,
                    onToggle = { category, on -> act({ blocker.list.setOn(category, on); true }, null) },
                    onAdd = { kind, value ->
                        act({
                            if (kind == BlockRule.NUMBER) blocker.blockNumber(value) else blocker.list.addRule(kind, value, System.currentTimeMillis())
                        }, "已添加", "已经有这条规则了")
                    },
                    onRemove = { id -> act({ blocker.list.removeRule(id) }, "已删除") },
                )
            } else {
                Text(
                    "被拦截的短信不进收件箱、不响铃，保留 30 天；拦截只影响手机，短信照常上报到平台。右滑放回收件箱，左滑删除，长按多选。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (messages.isEmpty()) {
                    Text("没有被拦截的短信", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(messages, key = { it.id }) { sms ->
                        SwipeRow(
                            start = SwipeAction(Icons.Filled.Done, "放回收件箱", toneColors(Tone.OK).second) { moveToInbox(listOf(sms)) },
                            end = SwipeAction(Icons.Filled.Delete, "删除", MaterialTheme.colorScheme.error) { delete(listOf(sms)) },
                            enabled = !selecting,
                        ) {
                            StoredSmsRow(
                                title = ContactNames.lookup(context, sms.address) ?: sms.address,
                                time = shortTime(sms.date),
                                body = sms.body,
                                note = SpamFilter.describe(sms.reason, sms.detail) + " · 剩 ${daysLeft(sms.blockedAt, now)} 天",
                                selecting = selecting,
                                selected = sms.id in selected,
                                onToggle = { selected = selected.toggle(sms.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = selected.toggle(sms.id)
                                },
                            )
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                    }
                }
            }
        }
    }
    SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空拦截记录？") },
            text = { Text("${messages.size} 条被拦截的短信会被彻底删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    act({ blocker.list.clear() >= 0 }, "已清空")
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

private fun daysLeft(blockedAt: Long, now: Long): Int =
    ((blockedAt + SmsTrash.RETENTION_MS - now + 86_399_999) / 86_400_000).toInt().coerceAtLeast(0)

@Composable
private fun RulesPane(
    rules: List<BlockRule>,
    onToggle: (SpamCategory, Boolean) -> Unit,
    onAdd: (kind: String, value: String) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val on = rules.mapNotNullTo(HashSet()) { SpamCategory.ofKind(it.kind) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("智能识别", icon = Icons.Filled.Lock) {
                Text(
                    "按内容识别常见的骚扰短信。验证码短信、通讯录联系人和信任号码永远不会被识别规则或关键词拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.fillMaxWidth()) {
                    SpamCategory.entries.forEachIndexed { i, category ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(category.label, fontWeight = FontWeight.Medium)
                                Text(category.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = category in on, onCheckedChange = { onToggle(category, it) })
                        }
                    }
                }
            }
        }
        item {
            RuleCard(
                title = "号码黑名单",
                hint = "这些号码发来的短信全部拦截，验证码也不例外。末尾加 * 表示拦截这个开头的所有号码，例如 1069*。",
                label = "号码",
                rules = rules.filter { it.kind == BlockRule.NUMBER },
                onAdd = { onAdd(BlockRule.NUMBER, it) },
                onRemove = onRemove,
            )
        }
        item {
            RuleCard(
                title = "信任号码",
                hint = "这些号码的短信永远不拦截，优先于黑名单和所有识别规则。同样支持末尾的 *，例如 95*。",
                label = "号码",
                rules = rules.filter { it.kind == BlockRule.ALLOW },
                onAdd = { onAdd(BlockRule.ALLOW, it) },
                onRemove = onRemove,
            )
        }
        item {
            RuleCard(
                title = "关键词",
                hint = "短信里含有这个词就拦截，会忽略短信里夹杂的空格和符号。用空格隔开多个词表示要同时出现，例如“会员 续费”。",
                label = "关键词",
                rules = rules.filter { it.kind == BlockRule.KEYWORD },
                onAdd = { onAdd(BlockRule.KEYWORD, it) },
                onRemove = onRemove,
            )
        }
        if (on.isEmpty() && rules.none { it.kind == BlockRule.NUMBER || it.kind == BlockRule.KEYWORD }) {
            item { Banner(Tone.WARN, Icons.Filled.Warning, "现在没有任何拦截规则，所有短信都会进收件箱。") }
        }
    }
}

@Composable
private fun RuleCard(title: String, hint: String, label: String, rules: List<BlockRule>, onAdd: (String) -> Unit, onRemove: (Long) -> Unit) {
    var value by rememberSaveable(title) { mutableStateOf("") }
    SectionCard(if (rules.isEmpty()) title else "$title（${rules.size}）") {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.weight(1f),
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            FilledTonalButton(enabled = value.isNotBlank(), onClick = {
                onAdd(value)
                value = ""
            }) { Text("添加") }
        }
        if (rules.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                rules.forEachIndexed { i, rule ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rule.value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        IconButton(onClick = { onRemove(rule.id) }) { Icon(Icons.Filled.Close, contentDescription = "删除 ${rule.value}") }
                    }
                }
            }
        }
    }
}
