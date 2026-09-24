package app.nya.smsforward.node.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.inbox.ContactNames
import app.nya.smsforward.node.inbox.ConversationSummary
import app.nya.smsforward.node.inbox.Conversations
import app.nya.smsforward.node.inbox.InboxNotifier
import app.nya.smsforward.node.inbox.LocalSender
import app.nya.smsforward.node.inbox.SmsRow
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.SimSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Bumps whenever the SMS database changes (a message arrived, was sent, read or deleted), so lists reload. */
@Composable
private fun rememberSmsChanges(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                tick++
            }
        }
        runCatching { context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return tick
}

/** The full edition asks to become the default SMS app; everything that writes needs it. */
@Composable
private fun DefaultAppCard(onRequest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设为默认短信应用", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "设为默认后，才能在这里收发短信，平台上的“同时删除手机短信”也才能真正删掉。原来的短信都会保留，随时可以在系统设置里换回去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = onRequest) { Text("设为默认") }
        }
    }
}

@Composable
fun InboxScreen(resumeTick: Int, onOpen: (threadId: Long, address: String) -> Unit, onNew: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SmsStore(context) }
    val changes = rememberSmsChanges()
    var recheck by remember { mutableIntStateOf(0) }
    val isDefault = remember(resumeTick, recheck) { store.isDefaultApp() }
    val canRead = remember(resumeTick, recheck) { store.canRead() }
    val canContacts = remember(resumeTick, recheck) { ContactNames.canRead(context) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck++ }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ContactNames.clear()
        recheck++
    }
    val conversations by produceState(emptyList<ConversationSummary>(), changes, canRead, canContacts) {
        value = withContext(Dispatchers.IO) { Conversations.group(store.recent()) }
    }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("短信", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (isDefault) FilledTonalButton(onClick = onNew) { Text("＋ 新短信") }
            }
            if (!isDefault) DefaultAppCard { roleLauncher.launch(SmsStore.requestDefaultIntent(context)) }
            if (!canRead) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("需要“读取短信”权限才能显示手机里的短信。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { permLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)) }) { Text("授予权限") }
                }
            } else if (!canContacts) {
                TextButton(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onClick = { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
                ) { Text("显示联系人姓名") }
            }
            if (canRead && conversations.isEmpty()) {
                Text("还没有短信", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(conversations, key = { "${it.threadId}:${it.address}" }) { c ->
                    ConversationRow(c, onClick = { onOpen(c.threadId, c.address) }, onLongClick = { if (isDefault) deleting = c })
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
                }
            }
        }
    }

    deleting?.let { c ->
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除会话？") },
            text = { Text("与 ${displayName(context, c.address)} 的全部短信会从手机上删除，无法恢复。平台上已上报的记录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch(Dispatchers.IO) { store.deleteThread(c.threadId) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

private fun displayName(context: Context, address: String): String = ContactNames.lookup(context, address) ?: address

@Composable
private fun Avatar(label: String) {
    val palette = listOf(Color(0xFF4964D8), Color(0xFF0E9384), Color(0xFFDC6803), Color(0xFF7A5AF8), Color(0xFFD92D20), Color(0xFF2E90FA))
    Box(
        modifier = Modifier.size(44.dp).background(palette[(label.hashCode() and 0x7fffffff) % palette.size], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.trim().firstOrNull()?.uppercase() ?: "#", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val name = remember(c.address) { displayName(context, c.address) }
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                )
                Text(shortTime(c.last.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.last.code?.let { CodeTag(it) }
                Text(
                    (if (c.last.incoming) "" else if (c.last.failed) "发送失败：" else "我：") + c.last.body,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (c.last.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (c.unread > 0) {
                    Box(
                        Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                    ) { Text(c.unread.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun CodeTag(code: String) {
    Text(
        code,
        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * One conversation. [threadId] may be 0 when it was opened by number (a "send to" intent or a new message); the rows are
 * then found by the normalized number.
 */
@Composable
fun ThreadScreen(runtime: NodeRuntime, threadId: Long, address: String, initialDraft: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SmsStore(context) }
    val changes = rememberSmsChanges()
    val scope = rememberCoroutineScope()
    val isDefault = remember(changes) { store.isDefaultApp() }
    val name = remember(address) { displayName(context, address) }
    val rows by produceState(emptyList<SmsRow>(), changes, threadId, address) {
        value = withContext(Dispatchers.IO) {
            val id = threadId.takeIf { it > 0 }
                ?: Conversations.group(store.recent()).firstOrNull { PeerKey.normalize(it.address) == PeerKey.normalize(address) }?.threadId
            if (id != null && id > 0) store.thread(id) else emptyList()
        }
    }
    val sims by produceState(emptyList<SimInfo>()) {
        value = withContext(Dispatchers.IO) { SimSlots.activeSims(context, runtime.settings.manualSimNumbers) }
    }
    var simChoice by rememberSaveable { mutableStateOf<Int?>(null) }
    val lastSub = rows.lastOrNull { it.incoming }?.subId ?: rows.lastOrNull()?.subId
    val sim = sims.firstOrNull { it.subscriptionId == (simChoice ?: lastSub) } ?: sims.firstOrNull()
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    var menuFor by remember { mutableStateOf<SmsRow?>(null) }
    val listState = rememberLazyListState()

    // Reading the conversation marks it read (only the default app may write that) and clears its notification.
    LaunchedEffect(rows) {
        val unread = rows.filter { it.incoming && !it.read }
        if (unread.isNotEmpty()) withContext(Dispatchers.IO) { store.markThreadRead(unread.first().threadId) }
        InboxNotifier.cancel(context, rows.firstOrNull()?.threadId ?: threadId, address)
        if (rows.isNotEmpty()) listState.scrollToItem(rows.size - 1)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ 返回", style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (name != address) Text(address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    Box {
                        Bubble(row, onLongClick = { menuFor = row })
                        DropdownMenu(expanded = menuFor?.id == row.id, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(text = { Text("复制全文") }, onClick = {
                                copy(context, "短信", row.body, "已复制")
                                menuFor = null
                            })
                            if (isDefault) {
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                                    menuFor = null
                                    scope.launch(Dispatchers.IO) { store.deleteMessage(row.id) }
                                })
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (!isDefault) {
                Text(
                    "设为默认短信应用后，才能在这里发送短信。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!PeerKey.isReplyable(address)) {
                Text(
                    "这个号码不能回复。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sims.size > 1 && sim != null) {
                        TextButton(onClick = {
                            val next = sims[(sims.indexOf(sim) + 1) % sims.size]
                            simChoice = next.subscriptionId
                        }) { Text("卡${sim.slot}") }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("短信") },
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                    )
                    Button(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            val text = draft.trim()
                            draft = ""
                            scope.launch(Dispatchers.IO) {
                                if (!LocalSender.send(context, address, text, sim?.subscriptionId)) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                    ) { Text("发送") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(row: SmsRow, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val incoming = row.incoming
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        Surface(
            color = if (incoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
            contentColor = if (incoming) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (incoming) 4.dp else 18.dp,
                bottomEnd = if (incoming) 18.dp else 4.dp,
            ),
            border = if (incoming) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongClick),
        ) {
            Text(row.body, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        row.code?.let { code ->
            Spacer(Modifier.size(4.dp))
            FilledTonalButton(
                onClick = { copy(context, "验证码", code, "验证码已复制") },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("复制验证码 $code", fontFamily = FontFamily.Monospace) }
        }
        Text(
            shortTime(row.date) + when {
                row.failed -> " · 发送失败"
                row.sending -> " · 发送中…"
                else -> ""
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (row.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Asks for a number, then opens that conversation. */
@Composable
fun NewMessageDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新短信") },
        text = {
            OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("收件人号码") }, singleLine = true)
        },
        confirmButton = {
            TextButton(enabled = PeerKey.isReplyable(number) && number.isNotBlank(), onClick = { onStart(number.trim()) }) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun copy(context: Context, label: String, text: String, toast: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** "14:05" today, "昨天", "09-24" this year, "2025-09-24" before. */
private fun shortTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameYear && dayDiff == 0 -> "HH:mm"
        sameYear && dayDiff == 1 -> return "昨天 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
        sameYear -> "MM-dd HH:mm"
        else -> "yyyy-MM-dd"
    }
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(millis))
}
