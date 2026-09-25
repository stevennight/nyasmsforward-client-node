package app.nya.smsforward.node.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val blocker = runtime.blocker
    var showRules by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val messages by produceState(emptyList<BlockedSms>(), reload) { value = withContext(Dispatchers.IO) { blocker.list() } }
    val rules by produceState(emptyList<BlockRule>(), reload) { value = withContext(Dispatchers.IO) { blocker.list.rules() } }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty() && !showRules
    val now = System.currentTimeMillis()

    fun act(block: () -> Boolean, done: String?, failed: String = "操作失败") {
        scope.launch(Dispatchers.IO) {
            val ok = block()
            withContext(Dispatchers.Main) {
                if (!ok) toast(context, failed) else if (done != null) toast(context, done)
                reload++
            }
        }
    }

    /** Runs [each] on every selected record and reports "N 条…" (plus how many failed). */
    fun batch(done: String, trust: Boolean = false, each: (BlockedSms) -> Boolean) {
        val picked = messages.filter { it.id in selected }
        selected = emptySet()
        scope.launch(Dispatchers.IO) {
            if (trust) picked.map { it.address }.distinct().forEach { blocker.trustNumber(it) }
            val ok = picked.count(each)
            withContext(Dispatchers.Main) {
                toast(context, if (ok == picked.size) "$ok 条$done" else "$ok 条$done，${picked.size - ok} 条失败（需要仍是默认短信应用）")
                reload++
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    total = messages.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = { all -> selected = if (all) messages.map { it.id }.toSet() else emptySet() },
                ) {
                    IconButton(onClick = { batch("已放回收件箱") { blocker.moveToInbox(it.id) } }) {
                        Icon(Icons.Filled.Done, contentDescription = "放回收件箱")
                    }
                    IconButton(onClick = { batch("已放回收件箱，号码已加入信任名单", trust = true) { blocker.moveToInbox(it.id) } }) {
                        Icon(Icons.Filled.Star, contentDescription = "信任号码并放回收件箱")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
            } else {
                NyaTopBar("骚扰拦截", onBack = onBack) {
                    if (!showRules && messages.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showRules, onClick = { showRules = false }, label = { Text("拦截记录（${messages.size}）") })
                FilterChip(selected = showRules, onClick = {
                    showRules = true
                    selected = emptySet()
                }, label = { Text("拦截规则") })
            }
            if (showRules) {
                RulesPane(
                    rules,
                    onToggle = { category, on -> act({ blocker.list.setCategory(category, on); true }, null) },
                    onAdd = { kind, value -> act({ blocker.list.addRule(kind, value, System.currentTimeMillis()) }, "已添加", "已经有这条规则了") },
                    onRemove = { id -> act({ blocker.list.removeRule(id) }, "已删除") },
                    preview = { address, body -> blocker.preview(address, body) },
                )
            } else {
                Text(
                    if (selecting) "点按选择更多；✓ 放回收件箱，★ 放回并信任号码（以后关键词和内置规则不再拦它）。"
                    else "被拦截的短信不进收件箱、不响铃，保留 30 天。拦截只影响手机，短信照常上报到平台。长按可多选。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (messages.isEmpty()) {
                    Text("没有被拦截的短信", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { sms ->
                        val isSelected = sms.id in selected
                        SelectableCard(
                            selected = isSelected,
                            selecting = selecting,
                            onClick = { if (selecting) selected = selected.toggle(sms.id) },
                            onLongClick = { selected = selected.toggle(sms.id) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (selecting) SelectMark(isSelected, size = 20)
                                Text(
                                    "${ContactNames.lookup(context, sms.address) ?: sms.address} · ${formatTime(sms.date)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(sms.body, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    SpamFilter.describe(sms.reason, sms.detail) + " · 剩 ${daysLeft(sms.blockedAt, now)} 天",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                if (!selecting) {
                                    TextButton(onClick = { act({ blocker.list.remove(sms.id) }, "已删除") }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                    FilledTonalButton(onClick = {
                                        act({ blocker.moveToInbox(sms.id) }, "已放回收件箱", "失败：需要仍是默认短信应用")
                                    }) { Text("放回收件箱") }
                                }
                            }
                        }
                    }
                }
            }
        }
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selected.size} 条拦截记录？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val ids = selected
                    selected = emptySet()
                    act({ blocker.list.removeAll(ids) >= 0 }, "已删除 ${ids.size} 条")
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** A card that can be long-pressed into a selection, highlighted while selected. */
@Composable
internal fun SelectableCard(
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = if (selecting) null else onLongClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
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
    preview: (address: String, body: String) -> String,
) {
    var number by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }
    var allow by rememberSaveable { mutableStateOf("") }
    val enabled = SpamFilter.enabledCategories(rules)
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("内置规则", icon = Icons.Filled.Lock) {
                Text(
                    "按类别识别常见骚扰短信，全部在手机上判断，不联网。验证码、银行收支、快递取件码、联系人和信任号码不会被这些规则拦。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.fillMaxWidth()) {
                    SpamCategory.entries.forEachIndexed { i, category ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(category.label + if (category.recommended) "  · 推荐" else "", fontWeight = FontWeight.Medium)
                                Text(category.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = category in enabled, onCheckedChange = { onToggle(category, it) })
                        }
                    }
                }
            }
        }
        item {
            SectionCard("号码黑名单") {
                Text(
                    "这些号码发来的短信全部拦截（包括验证码）。末尾加 * 表示拦截这个开头的所有号码，例如 1069*。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AddRow(number, { number = it }, "号码") {
                    onAdd(BlockRule.NUMBER, number)
                    number = ""
                }
                RuleList(rules.filter { it.kind == BlockRule.NUMBER }, onRemove)
            }
        }
        item {
            SectionCard("关键词") {
                Text(
                    "短信里含有这些词就拦截，例如“贷款”“博彩”。验证码短信不会被关键词拦截。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AddRow(keyword, { keyword = it }, "关键词") {
                    onAdd(BlockRule.KEYWORD, keyword)
                    keyword = ""
                }
                RuleList(rules.filter { it.kind == BlockRule.KEYWORD }, onRemove)
            }
        }
        item {
            SectionCard("信任号码") {
                Text(
                    "被误拦时把号码加到这里：关键词和内置规则不再拦它（黑名单仍然生效）。支持 106* 这样的前缀。通讯录里的联系人自动信任。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AddRow(allow, { allow = it }, "号码") {
                    onAdd(BlockRule.ALLOW, allow)
                    allow = ""
                }
                RuleList(rules.filter { it.kind == BlockRule.ALLOW }, onRemove)
            }
        }
        item { TryRules(preview) }
    }
}

/** "试一试": paste a message and see which rule, if any, would catch it. */
@Composable
private fun TryRules(preview: (address: String, body: String) -> String) {
    var address by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SectionCard("试一试") {
        Text(
            "粘贴一条短信，看看按现在的规则会不会被拦（按陌生号码判断）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; result = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("发件号码（可不填）") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it; result = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("短信内容") },
            minLines = 2,
            maxLines = 6,
            shape = RoundedCornerShape(12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                result.orEmpty(),
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
                color = if (result == "不会被拦截") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            FilledTonalButton(enabled = body.isNotBlank(), onClick = {
                scope.launch { result = withContext(Dispatchers.IO) { preview(address, body) } }
            }) { Text("检测") }
        }
    }
}

@Composable
private fun AddRow(value: String, onChange: (String) -> Unit, label: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        FilledTonalButton(enabled = value.isNotBlank(), onClick = onAdd) { Text("添加") }
    }
}

@Composable
private fun RuleList(rules: List<BlockRule>, onRemove: (Long) -> Unit) {
    if (rules.isEmpty()) {
        Text("还没有", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
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
