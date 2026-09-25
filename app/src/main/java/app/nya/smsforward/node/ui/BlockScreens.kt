package app.nya.smsforward.node.ui

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
import androidx.compose.material.icons.filled.Lock
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            NyaTopBar("骚扰拦截", onBack = onBack) {
                if (!showRules && messages.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showRules, onClick = { showRules = false }, label = { Text("拦截记录（${messages.size}）") })
                FilterChip(selected = showRules, onClick = { showRules = true }, label = { Text("拦截规则") })
            }
            if (showRules) {
                RulesPane(rules, onToggleMarketing = { on -> act({ blocker.list.marketingBlocked = on; true }, null) }, onAdd = { kind, value ->
                    act({ blocker.list.addRule(kind, value, System.currentTimeMillis()) }, "已添加", "已经有这条规则了")
                }, onRemove = { id -> act({ blocker.list.removeRule(id) }, "已删除") })
            } else {
                Text(
                    "被拦截的短信不进收件箱、不响铃，保留 30 天。拦截只影响手机，短信照常上报到平台。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (messages.isEmpty()) {
                    Text("没有被拦截的短信", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(messages, key = { it.id }) { sms ->
                        SectionCard("${ContactNames.lookup(context, sms.address) ?: sms.address} · ${formatTime(sms.date)}") {
                            Text(sms.body, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    SpamFilter.describe(sms.reason, sms.detail) + " · 剩 ${daysLeft(sms.blockedAt, now)} 天",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
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
    onToggleMarketing: (Boolean) -> Unit,
    onAdd: (kind: String, value: String) -> Unit,
    onRemove: (Long) -> Unit,
) {
    var number by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }
    val marketing = rules.any { it.kind == BlockRule.MARKETING }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("推广短信", icon = Icons.Filled.Lock) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "拦截带“回T退订”“拒收请回复R”这类字样的营销短信。验证码短信不会被拦。",
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(checked = marketing, onCheckedChange = onToggleMarketing)
                }
            }
        }
        item {
            SectionCard("号码黑名单") {
                Text(
                    "这些号码发来的短信全部拦截。末尾加 * 表示拦截这个开头的所有号码，例如 1069*。",
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
