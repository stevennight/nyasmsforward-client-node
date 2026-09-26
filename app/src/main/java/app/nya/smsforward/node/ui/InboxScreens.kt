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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.data.SmsTrash
import app.nya.smsforward.node.data.TrashedSms
import app.nya.smsforward.node.inbox.ContactNames
import app.nya.smsforward.node.inbox.ConversationSummary
import app.nya.smsforward.node.inbox.Conversations
import app.nya.smsforward.node.inbox.InboxFilter
import app.nya.smsforward.node.inbox.InboxNotifier
import app.nya.smsforward.node.inbox.LocalSender
import app.nya.smsforward.node.inbox.Recycler
import app.nya.smsforward.node.inbox.SmsRow
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.inbox.searchConversations
import app.nya.smsforward.node.inbox.senderBrand
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.SimSlots
import app.nya.smsforward.node.sms.SmsInsight
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

private fun keyOf(c: ConversationSummary) = "${c.threadId}:${c.address}"

/**
 * The conversation list. Swipe right to mark read / unread, swipe left to delete (with "撤销"); long-press starts
 * multi-select, where the top bar offers the actions for everything picked.
 */
@Composable
fun InboxScreen(
    runtime: NodeRuntime,
    resumeTick: Int,
    onOpen: (threadId: Long, address: String) -> Unit,
    onNew: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenBlocked: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SmsStore(context) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
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
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(InboxFilter.ALL) }
    val rows by produceState(emptyList<SmsRow>(), changes, canRead, canContacts) {
        value = withContext(Dispatchers.IO) { store.recent() }
    }
    val conversations by produceState(emptyList<ConversationSummary>(), rows, query, filter) {
        value = withContext(Dispatchers.Default) {
            searchConversations(rows, query) { ContactNames.lookup(context, it) }.filter { filter.matches(it) }
        }
    }
    val blockedCount by produceState(0, resumeTick, changes) {
        value = withContext(Dispatchers.IO) { runtime.blocker.list().size }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    val picked = conversations.filter { keyOf(it) in selected }
    // The recycle bin forgets what is older than 30 days whenever the inbox opens (the block list does in list()).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runtime.recycler.purge() } }
    // A conversation that went away (deleted, filtered out) is no longer selected.
    LaunchedEffect(conversations) {
        val present = conversations.mapTo(HashSet(), ::keyOf)
        if (!present.containsAll(selected)) selected = selected.intersect(present)
    }
    BackHandler(enabled = selecting) { selected = emptySet() }

    /** Moves conversations to the recycle bin and offers to undo it. Returns whether anything was moved. */
    suspend fun delete(targets: List<ConversationSummary>): Boolean {
        val ids = withContext(Dispatchers.IO) {
            runtime.recycler.recycleThreads(targets.map { it.threadId }.filter { it > 0 }, Recycler.ORIGIN_APP)
        }
        if (ids.isEmpty()) {
            toast(context, "删除失败")
            return false
        }
        scope.launch {
            val label = if (targets.size == 1) "已删除会话" else "已删除 ${targets.size} 个会话"
            if (snackbar.showSnackbar(label, actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { runtime.recycler.restoreAll(ids) }
            }
        }
        return true
    }

    fun markRead(targets: List<ConversationSummary>, read: Boolean) {
        scope.launch(Dispatchers.IO) {
            for (c in targets) {
                if (read) {
                    store.markThreadRead(c.threadId)
                    InboxNotifier.cancel(context, c.threadId, c.address)
                } else {
                    store.markThreadUnread(c.threadId)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            when {
                selecting -> SelectionTopBar(selected.size, onClose = { selected = emptySet() }) {
                    var more by remember { mutableStateOf(false) }
                    TextButton(onClick = {
                        selected = if (selected.size == conversations.size) emptySet() else conversations.mapTo(HashSet(), ::keyOf)
                    }) { Text(if (selected.size == conversations.size) "全不选" else "全选") }
                    IconButton(onClick = {
                        markRead(picked, true)
                        selected = emptySet()
                    }) { Icon(Icons.Filled.Done, contentDescription = "标为已读") }
                    IconButton(onClick = {
                        val targets = picked
                        selected = emptySet()
                        scope.launch { delete(targets) }
                    }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("标为未读") }, onClick = {
                                more = false
                                markRead(picked, false)
                                selected = emptySet()
                            })
                            DropdownMenuItem(text = { Text("拦截这些号码") }, onClick = {
                                more = false
                                val numbers = picked.map { it.address }.filter { it.isNotBlank() }.distinct()
                                selected = emptySet()
                                scope.launch(Dispatchers.IO) {
                                    val added = numbers.count { runtime.blocker.blockNumber(it) }
                                    withContext(Dispatchers.Main) {
                                        toast(context, if (added > 0) "以后这 $added 个号码的短信会进“骚扰拦截”" else "这些号码已经在拦截名单里")
                                    }
                                }
                            })
                        }
                    }
                }
                searching -> {
                    val close = {
                        searching = false
                        query = ""
                    }
                    BackHandler(onBack = close)
                    SearchBar(query, onChange = { query = it }, onClose = close)
                }
                else -> NyaTopBar("短信") {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                    if (isDefault) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (blockedCount > 0) "骚扰拦截（$blockedCount）" else "骚扰拦截") },
                                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenBlocked()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("回收站") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenTrash()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("全部标为已读") },
                                    leadingIcon = { Icon(Icons.Filled.Done, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch(Dispatchers.IO) {
                                            val n = store.markAllRead()
                                            withContext(Dispatchers.Main) { toast(context, if (n > 0) "已将 $n 条标为已读" else "没有未读短信") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
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
            if (canRead && !selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (f in InboxFilter.entries) {
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            if (canRead && conversations.isEmpty()) {
                Text(
                    when {
                        query.isNotBlank() -> "没有找到“${query.trim()}”"
                        filter != InboxFilter.ALL -> "没有${filter.label}短信"
                        else -> "还没有短信"
                    },
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(conversations, key = ::keyOf) { c ->
                    val key = keyOf(c)
                    val isSelected = key in selected
                    SwipeRow(
                        start = if (c.unread > 0) {
                            SwipeAction(Icons.Filled.Done, "标为已读", MaterialTheme.colorScheme.primary) { markRead(listOf(c), true); false }
                        } else {
                            SwipeAction(Icons.Filled.Email, "标为未读", MaterialTheme.colorScheme.primary) { markRead(listOf(c), false); false }
                        },
                        end = SwipeAction(Icons.Filled.Delete, "删除", MaterialTheme.colorScheme.error) { delete(listOf(c)) },
                        enabled = isDefault && !selecting,
                    ) {
                        ConversationRow(
                            c,
                            selected = isSelected,
                            onClick = { if (selecting) selected = selected.toggle(key) else onOpen(c.threadId, c.address) },
                            onLongClick = {
                                if (isDefault) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = selected.toggle(key)
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .6f))
                }
            }
        }
    }
    AnimatedVisibility(
        visible = isDefault && !searching && !selecting,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
    ) {
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            text = { Text("新短信") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
    SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** A full-width choice in a dialog's option list. */
@Composable
internal fun MenuLine(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** The search field that replaces the title while searching. */
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        OutlinedTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("搜索短信、号码或联系人") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "清除") }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

internal fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun displayName(context: Context, address: String): String = ContactNames.lookup(context, address) ?: address

@Composable
private fun Avatar(name: String, address: String, size: Int = 44) {
    val palette = listOf(Color(0xFF4964D8), Color(0xFF0E9384), Color(0xFFDC6803), Color(0xFF7A5AF8), Color(0xFFD92D20), Color(0xFF2E90FA))
    val hasName = name != address && name.isNotBlank()
    Box(
        modifier = Modifier.size(size.dp).background(palette[(name.hashCode() and 0x7fffffff) % palette.size], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            hasName -> Text(name.trim().first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            PeerKey.normalize(address).matches(Regex("1[3-9]\\d{9}")) ->
                Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White)
            else -> Icon(Icons.Filled.Email, contentDescription = null, tint = Color.White, modifier = Modifier.size((size / 2).dp))
        }
    }
}

/** The background of a picked row in multi-select. */
@Composable
private fun selectedTint() = MaterialTheme.colorScheme.primaryContainer

@Composable
private fun ConversationRow(c: ConversationSummary, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val contact = remember(c.address) { ContactNames.lookup(context, c.address) }
    val name = contact ?: senderBrand(c.last.body) ?: c.address
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) selectedTint() else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected) SelectedMark() else Avatar(name, c.address)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (c.unread > 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                )
                if (name != c.address) {
                    Text(
                        "  " + c.address,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    shortTime(c.last.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (c.unread > 0) FontWeight.Bold else null,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val code = c.last.code
                val insight = c.last.insight
                when {
                    code != null -> CodeTag(code)
                    insight is SmsInsight.Parcel -> InsightTag("取件 ${insight.code}", Tone.WARN)
                    insight is SmsInsight.Bank -> InsightTag((if (insight.income) "+" else "-") + insight.amount, if (insight.income) Tone.OK else Tone.BAD)
                }
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

@Composable
private fun InsightTag(text: String, tone: Tone) {
    val (bg, fg) = toneColors(tone)
    Text(
        text,
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** Under a bank or parcel message: the recognized facts at a glance, and the pickup code one tap from the clipboard. */
@Composable
private fun InsightCard(insight: SmsInsight) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.widthIn(min = 200.dp, max = 300.dp),
    ) {
        when (insight) {
            is SmsInsight.Bank -> Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    (if (insight.income) "收入" else "支出") + (insight.cardTail?.let { " · 尾号 $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    (if (insight.income) "+" else "-") + insight.amount + " 元",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = toneColors(if (insight.income) Tone.OK else Tone.BAD).second,
                )
                insight.balance?.let {
                    Text("余额 $it 元", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is SmsInsight.Parcel -> Row(
                Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("取件码", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(insight.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { copy(context, "取件码", insight.code, "取件码已复制") }) { Text("复制") }
            }
        }
    }
}

/**
 * One conversation. [threadId] may be 0 when it was opened by number (a "send to" intent or a new message); the rows are
 * then found by the normalized number. Long-press a message to pick several, then copy or delete them together.
 */
@Composable
fun ThreadScreen(runtime: NodeRuntime, threadId: Long, address: String, initialDraft: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SmsStore(context) }
    val changes = rememberSmsChanges()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val isDefault = remember(changes) { store.isDefaultApp() }
    val contact = remember(address) { ContactNames.lookup(context, address) }
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
    val name = contact ?: rows.asReversed().firstNotNullOfOrNull { senderBrand(it.body) } ?: address
    var simChoice by rememberSaveable { mutableStateOf<Int?>(null) }
    val lastSub = rows.lastOrNull { it.incoming }?.subId ?: rows.lastOrNull()?.subId
    val sim = sims.firstOrNull { it.subscriptionId == (simChoice ?: lastSub) } ?: sims.firstOrNull()
    var draft by rememberSaveable { mutableStateOf(initialDraft) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()
    val listState = rememberLazyListState()

    // Reading the conversation marks it read (only the default app may write that) and clears its notification.
    LaunchedEffect(rows) {
        val unread = rows.filter { it.incoming && !it.read }
        if (unread.isNotEmpty()) withContext(Dispatchers.IO) { store.markThreadRead(unread.first().threadId) }
        InboxNotifier.cancel(context, rows.firstOrNull()?.threadId ?: threadId, address)
        if (rows.isNotEmpty() && !selecting) listState.scrollToItem(rows.size - 1)
        val present = rows.mapTo(HashSet()) { it.id }
        if (!present.containsAll(selected)) selected = selected.intersect(present)
    }
    BackHandler(enabled = selecting) { selected = emptySet() }

    Box(Modifier.fillMaxSize()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (selecting) {
                SelectionTopBar(selected.size, onClose = { selected = emptySet() }) {
                    TextButton(onClick = {
                        copy(context, "短信", rows.filter { it.id in selected }.joinToString("\n\n") { it.body }, "已复制 ${selected.size} 条")
                        selected = emptySet()
                    }) { Text("复制") }
                    if (isDefault) {
                        IconButton(onClick = {
                            val ids = selected
                            selected = emptySet()
                            scope.launch {
                                val trashIds = withContext(Dispatchers.IO) { runtime.recycler.recycleAll(ids, Recycler.ORIGIN_APP) }
                                if (trashIds.isEmpty()) {
                                    toast(context, "删除失败")
                                } else if (snackbar.showSnackbar("已将 ${trashIds.size} 条移到回收站", actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                                    withContext(Dispatchers.IO) { runtime.recycler.restoreAll(trashIds) }
                                }
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                    }
                }
            } else {
                NyaTopBar(name, onBack = onBack, subtitle = if (name != address) address else null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    Bubble(
                        row,
                        selected = row.id in selected,
                        onClick = { if (selecting) selected = selected.toggle(row.id) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selected = selected.toggle(row.id)
                        },
                    )
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
                        FilledTonalButton(
                            onClick = { simChoice = sims[(sims.indexOf(sim) + 1) % sims.size].subscriptionId },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) { Text("卡${sim.slot}") }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("短信") },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                    )
                    FilledIconButton(
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.size(52.dp),
                        onClick = {
                            val text = draft.trim()
                            draft = ""
                            scope.launch(Dispatchers.IO) {
                                if (!LocalSender.send(context, address, text, sim?.subscriptionId)) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送") }
                }
            }
        }
    }
    SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
}

@Composable
private fun Bubble(row: SmsRow, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val incoming = row.incoming
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) selectedTint() else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 2.dp),
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
            border = if (incoming) androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) else null,
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        row.insight?.let {
            Spacer(Modifier.size(4.dp))
            InsightCard(it)
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

/**
 * The phone's recycle bin: what was deleted here or from the platform, restorable for 30 days. Swipe right to restore,
 * left to delete for good; long-press to pick several.
 */
@Composable
fun RecycleBinScreen(runtime: NodeRuntime, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var reload by remember { mutableIntStateOf(0) }
    val items by produceState(emptyList<TrashedSms>(), reload) {
        value = withContext(Dispatchers.IO) { runtime.recycler.list() }
    }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()
    // What "彻底删除" / "清空" would remove, waiting for the confirmation.
    var confirmDelete by remember { mutableStateOf<Set<Long>?>(null) }
    val now = System.currentTimeMillis()
    BackHandler(enabled = selecting) { selected = emptySet() }

    fun restore(ids: Collection<Long>) {
        scope.launch(Dispatchers.IO) {
            val n = runtime.recycler.restoreAll(ids)
            withContext(Dispatchers.Main) {
                toast(context, if (n == ids.size) "已恢复 $n 条" else if (n > 0) "已恢复 $n 条，其余失败" else "恢复失败：需要仍是默认短信应用")
                reload++
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionTopBar(selected.size, onClose = { selected = emptySet() }) {
                    TextButton(onClick = {
                        selected = if (selected.size == items.size) emptySet() else items.mapTo(HashSet()) { it.id }
                    }) { Text(if (selected.size == items.size) "全不选" else "全选") }
                    IconButton(onClick = {
                        restore(selected)
                        selected = emptySet()
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "恢复") }
                    IconButton(onClick = { confirmDelete = selected }) { Icon(Icons.Filled.Delete, contentDescription = "彻底删除") }
                }
            } else {
                NyaTopBar("回收站", onBack = onBack) {
                    if (items.isNotEmpty()) TextButton(onClick = { confirmDelete = items.mapTo(HashSet()) { it.id } }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                }
            }
            Text(
                "删除的短信在这里保留 30 天，期间可以恢复到手机短信里；网页上“同时删除手机短信”删掉的也在这里。右滑恢复，左滑彻底删除，长按多选。",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (items.isEmpty()) Text("回收站是空的", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(items, key = { it.id }) { sms ->
                    SwipeRow(
                        start = SwipeAction(Icons.Filled.Refresh, "恢复", toneColors(Tone.OK).second) {
                            val ok = withContext(Dispatchers.IO) { runtime.recycler.restore(sms.id) }
                            toast(context, if (ok) "已恢复" else "恢复失败：需要仍是默认短信应用")
                            if (ok) reload++
                            ok
                        },
                        end = SwipeAction(Icons.Filled.Delete, "彻底删除", MaterialTheme.colorScheme.error) {
                            confirmDelete = setOf(sms.id)
                            false
                        },
                        enabled = !selecting,
                    ) {
                        StoredSmsRow(
                            title = "${displayName(context, sms.address)} · ${if (sms.type == Telephony.Sms.MESSAGE_TYPE_INBOX) "收到" else "发出"}",
                            time = shortTime(sms.date),
                            body = sms.body,
                            note = (if (sms.origin == Recycler.ORIGIN_PLATFORM) "网页删除" else "手机上删除") + " · 剩 ${SmsTrash.daysLeft(sms, now)} 天",
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

    confirmDelete?.let { ids ->
        val all = ids.size == items.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (all) "清空回收站？" else "彻底删除 ${ids.size} 条？") },
            text = { Text("${ids.size} 条短信会被彻底删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    selected = emptySet()
                    scope.launch(Dispatchers.IO) {
                        if (all) runtime.recycler.empty() else ids.forEach { runtime.recycler.deleteForever(it) }
                        withContext(Dispatchers.Main) {
                            toast(context, if (all) "回收站已清空" else "已彻底删除")
                            reload++
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

/** A kept message in the recycle bin or the block list: who, when, the text (tap to show all), and a note (why / days left). */
@Composable
internal fun StoredSmsRow(
    title: String,
    time: String,
    body: String,
    note: String,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) selectedTint() else Color.Transparent)
            .combinedClickable(onClick = { if (selecting) onToggle() else expanded = !expanded }, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected) SelectedMark(size = 36)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                body,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
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

internal fun copy(context: Context, label: String, text: String, toast: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** "14:05" today, "昨天", "09-24" this year, "2025-09-24" before. */
internal fun shortTime(millis: Long): String {
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
