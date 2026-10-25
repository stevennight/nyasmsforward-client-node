package app.nya.smsforward.node.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.inbox.ContactNames
import app.nya.smsforward.node.inbox.senderBrand
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.view.OutboundTask
import app.nya.smsforward.node.view.PlatformFilter
import app.nya.smsforward.node.view.RemoteConversation
import app.nya.smsforward.node.view.RemoteMessage
import app.nya.smsforward.node.view.RemotePhone
import app.nya.smsforward.node.view.ServerEvent
import app.nya.smsforward.node.view.StreamStatus
import app.nya.smsforward.node.view.ViewApi
import app.nya.smsforward.node.view.ViewConnection
import app.nya.smsforward.node.view.ViewState
import app.nya.smsforward.node.view.belongsTo
import app.nya.smsforward.node.view.describeViewFailure
import app.nya.smsforward.node.view.sourceLabel
import kotlinx.coroutines.launch

/** A conversation's title: the contact's name, the brand in 【】, or the number. */
@Composable
private fun titleOf(peer: String, lastBody: String): String {
    val context = LocalContext.current
    return remember(peer) { ContactNames.lookup(context, peer) } ?: senderBrand(lastBody) ?: peer
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Live-stream trouble worth a line above the list; nothing while it is connected. */
@Composable
private fun StreamBanner(state: ViewState) {
    when (state.stream) {
        StreamStatus.WAITING -> Banner(Tone.WARN, Icons.Filled.Refresh, "实时更新已断开，正在重新连接…")
        StreamStatus.FORBIDDEN -> Banner(Tone.BAD, Icons.Filled.Lock, "这个客户端没有“查看”权限，可以在网页的设备页里勾上。")
        else -> Unit
    }
}

/**
 * Asks before moving to the platform recycle bin; [onConfirm] gets whether the receiver phone should delete its copy
 * too. One dialog for every platform delete (messages, conversations), the same choice the web console offers.
 */
@Composable
fun PlatformDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: (deletePhone: Boolean) -> Unit) {
    var deletePhone by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                Row(
                    Modifier.fillMaxWidth().combinedClickableCompat({ deletePhone = !deletePhone }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deletePhone, onCheckedChange = { deletePhone = it })
                    Column(Modifier.weight(1f)) {
                        Text("同时删除手机上的原短信")
                        Text(
                            "那台接收端开启了“短信应用”并设为默认短信应用才能删掉，会先放进它的手机回收站（30 天内可恢复）；手机离线时会在重新连上后处理。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deletePhone) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * The 「平台」 tab: every conversation on the platform, from every receiver phone, newest first. Search and "未读" are
 * asked of the server; the phone chips filter what came back. Updates live while the app is open.
 */
@Composable
fun PlatformScreen(runtime: NodeRuntime, onOpen: (RemoteConversation) -> Unit, onOpenSettings: () -> Unit, onOpenTrash: () -> Unit) {
    val view = runtime.view
    val state by view.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RemoteConversation?>(null) }
    LaunchedEffect(Unit) { view.refresh() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (searching) {
                SearchBar(state.query, view::setQuery, onClose = { searching = false; view.setQuery("") }, placeholder = "搜索平台上的短信或号码")
            } else {
                NyaTopBar("平台", subtitle = if (state.unread > 0) "${state.unread} 条未读" else null) {
                    if (state.connection == ViewConnection.CONNECTED) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                        IconButton(onClick = { view.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (state.canDelete) {
                                    DropdownMenuItem(text = { Text("平台回收站") }, onClick = { menu = false; onOpenTrash() })
                                }
                                if (state.ownInInbox) {
                                    DropdownMenuItem(
                                        text = { Text(if (state.showOwn) "「全部」里不列本机短信" else "「全部」里也列本机短信") },
                                        onClick = { menu = false; view.setShowOwn(!state.showOwn) },
                                    )
                                }
                                DropdownMenuItem(text = { Text("查看设置") }, onClick = { menu = false; onOpenSettings() })
                            }
                        }
                    }
                }
            }

            if (state.connection != ViewConnection.CONNECTED) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard(if (state.connection == ViewConnection.NEEDS_LOGIN) "登录已失效" else "还没有登录") {
                        Text(
                            "「平台」显示所有手机上报到服务器的短信。要用一个“客户端”身份登录：网页里生成的客户端配对码，或者管理员密码。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenSettings) { Text("去登录") }
                    }
                }
                return@Box
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StreamBanner(state)
                state.error?.let { Banner(Tone.BAD, Icons.Filled.Warning, it) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = state.filter == PlatformFilter.All, onClick = { view.setFilter(PlatformFilter.All) }, label = { Text("全部") })
                FilterChip(selected = state.filter == PlatformFilter.Unread, onClick = { view.setFilter(PlatformFilter.Unread) }, label = { Text("未读") })
                if (state.ownDeviceId != null) {
                    FilterChip(selected = state.filter == PlatformFilter.ThisPhone, onClick = { view.setFilter(PlatformFilter.ThisPhone) }, label = { Text("本机") })
                }
                for (phone in state.phones.values.filter { it.id != state.ownDeviceId }.sortedBy { it.name }) {
                    val f = PlatformFilter.Phone(phone.id)
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { view.setFilter(f) },
                        label = { Text(phone.name.ifBlank { "未命名手机" } + if (phone.online) "" else "（离线）") },
                    )
                }
            }

            val shown = state.shown
            when {
                !state.loaded -> Text("加载中…", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.isEmpty() -> Text(
                    when {
                        state.query.isNotBlank() -> "没有找到“${state.query.trim()}”"
                        state.filter == PlatformFilter.Unread -> "没有未读短信"
                        state.filter != PlatformFilter.All -> "这台手机还没有短信"
                        state.hiddenOwn > 0 -> "其他手机还没有短信"
                        else -> "平台上还没有短信"
                    },
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.key }) { c ->
                    PlatformConversationRow(
                        c, state,
                        onClick = { onOpen(c) },
                        onMarkRead = if (c.unread > 0) {
                            { scope.launch { (view.markThreadRead(c) as? ApiResult.Failure)?.let { toast(context, describeViewFailure(it)) } } }
                        } else {
                            null
                        },
                        onDelete = if (state.canDelete) ({ deleting = c }) else null,
                    )
                }
                if (state.hiddenOwn > 0) {
                    item(key = "own-hint") {
                        Text(
                            "本机的 ${state.hiddenOwn} 个会话在「短信」页里，点上面的「本机」可以在这里看。",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.connection == ViewConnection.CONNECTED && state.canSend) {
            ExtendedFloatingActionButton(
                onClick = { composing = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新短信") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (composing) {
        NewPlatformMessageDialog(runtime, state, onDismiss = { composing = false })
    }
    deleting?.let { c ->
        PlatformDeleteDialog(
            title = "删除这个会话？",
            message = "和 ${c.peer} 的全部短信会移到平台回收站，30 天内可以恢复。",
            onDismiss = { deleting = null },
            onConfirm = { phone ->
                deleting = null
                scope.launch {
                    when (val r = view.deleteConversations(listOf(c), phone)) {
                        is ApiResult.Ok -> toast(context, "已移到平台回收站（${r.value} 条）")
                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                    }
                }
            },
        )
    }
}

@Composable
private fun PlatformConversationRow(c: RemoteConversation, state: ViewState, onClick: () -> Unit, onMarkRead: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val name = titleOf(c.peer, c.last.body)
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .combinedClickableCompat(onClick, onLongClick = if (onMarkRead != null || onDelete != null) ({ menu = true }) else null)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(name, c.peer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (c.unread > 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        shortTime(c.last.deviceTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (c.unread > 0) FontWeight.Bold else null,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    c.last.code?.let { CodeTag(it) }
                    Text(
                        (if (c.last.incoming) "" else "我：") + c.last.body,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (c.unread > 0) {
                        Box(Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp)) {
                            Text(c.unread.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Pill(sourceLabel(c.deviceId, c.cardNumber, state), if (c.deviceId == state.ownDeviceId) Tone.OK else Tone.INFO)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            onMarkRead?.let { DropdownMenuItem(text = { Text("标为已读") }, onClick = { menu = false; it() }) }
            onDelete?.let { DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; it() }) }
        }
    }
    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
}

/** A new message through a receiver phone the platform may make send it (policy "允许新发"). */
@Composable
private fun NewPlatformMessageDialog(runtime: NodeRuntime, state: ViewState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phones = state.phones.values.filter { !it.revoked }.sortedWith(compareBy<RemotePhone> { it.blockedReason(reply = false) != null }.thenBy { it.name })
    var phoneId by remember { mutableStateOf(phones.firstOrNull { it.blockedReason(reply = false) == null }?.id ?: phones.firstOrNull()?.id) }
    val phone = phones.firstOrNull { it.id == phoneId }
    var slot by remember(phoneId) { mutableStateOf(phone?.sims?.firstOrNull()?.slot) }
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val blocked = phone?.blockedReason(reply = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新短信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phones.isEmpty()) Text("平台上还没有接收端手机。")
                Text("用哪台手机发", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in phones) {
                        FilterChip(
                            selected = p.id == phoneId,
                            onClick = { phoneId = p.id },
                            label = { Text((if (p.id == state.ownDeviceId) "本机" else p.name.ifBlank { "未命名手机" }) + if (p.online) "" else "（离线）") },
                        )
                    }
                }
                if (phone != null && phone.sims.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (sim in phone.sims) {
                            FilterChip(selected = sim.slot == slot, onClick = { slot = sim.slot }, label = { Text("卡${sim.slot}" + (sim.label?.let { " $it" } ?: "")) })
                        }
                    }
                }
                blocked?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = to, onValueChange = { to = it; error = null }, label = { Text("收件人号码") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = body, onValueChange = { body = it; error = null }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && phone != null && blocked == null && to.isNotBlank() && body.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        when (val r = runtime.view.sendNew(phone!!.id, slot, to, body)) {
                            is ApiResult.Ok -> {
                                toast(context, "已交给手机：" + r.value.summary)
                                onDismiss()
                            }
                            is ApiResult.Failure -> error = describeViewFailure(r)
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "发送中…" else "发送") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * One platform conversation. Opening it marks it read on the server; older messages load when scrolling to the top.
 * New messages, reads, deletions and send-task updates from the event stream show up while it is open. Replies go out
 * through the phone that received the newest message.
 */
@Composable
fun PlatformThreadScreen(runtime: NodeRuntime, thread: RemoteConversation, onBack: () -> Unit) {
    val view = runtime.view
    val state by view.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<RemoteMessage>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<OutboundTask>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var settled by remember { mutableStateOf(false) } // scrolled to the newest message after the first load
    var loadingOlder by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var deleteMessage by remember { mutableStateOf<RemoteMessage?>(null) }
    var deleteThread by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // While it is on screen, its messages do not pop up as notifications.
    DisposableEffect(thread.key) {
        view.openConversation = thread.key
        onDispose { if (view.openConversation == thread.key) view.openConversation = null }
    }

    fun sorted(list: List<RemoteMessage>) = list.distinctBy { it.id }.sortedWith(compareBy<RemoteMessage> { it.deviceTime }.thenBy { it.id })

    suspend fun loadTasks() {
        (view.tasks(thread) as? ApiResult.Ok)?.let { r -> tasks = r.value }
    }

    suspend fun loadLatest() {
        when (val r = view.loadThread(thread)) {
            is ApiResult.Ok -> {
                // The newest page replaces what it covers; older pages loaded by scrolling up stay.
                val oldestNew = r.value.minOfOrNull { it.id }
                messages = sorted(r.value + messages.filter { oldestNew != null && it.id < oldestNew })
                if (!settled) hasMore = r.value.size >= ViewApi.PAGE
                error = null
            }
            is ApiResult.Failure -> error = describeViewFailure(r)
        }
        loading = false
        if (messages.any { it.unread }) view.markThreadRead(thread)
    }

    suspend fun loadOlder() {
        val oldest = messages.minOfOrNull { it.id } ?: return
        loadingOlder = true
        when (val r = view.loadThread(thread, before = oldest)) {
            is ApiResult.Ok -> {
                val before = messages.size
                messages = sorted(r.value + messages)
                hasMore = r.value.size >= ViewApi.PAGE
                // Keep the message that was on top where it was.
                listState.scrollToItem((messages.size - before).coerceAtLeast(0) + 1)
            }
            is ApiResult.Failure -> error = describeViewFailure(r)
        }
        loadingOlder = false
    }

    LaunchedEffect(thread.key) {
        loadLatest()
        loadTasks()
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size)
        settled = true
    }
    // Live updates while open.
    LaunchedEffect(thread.key) {
        view.events.collect { e ->
            when (e) {
                is ServerEvent.Message -> if (e.message.belongsTo(thread)) {
                    val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= messages.size - 1 } ?: true
                    messages = sorted(messages + e.message)
                    if (e.message.unread) view.markThreadRead(thread)
                    if (atBottom) listState.animateScrollToItem(messages.size)
                }
                is ServerEvent.Read -> messages = messages.map { if (it.id in e.ids && it.readAt == null) it.copy(readAt = System.currentTimeMillis()) else it }
                is ServerEvent.Deleted -> messages = messages.filter { it.id !in e.ids }
                is ServerEvent.Outbound -> if (tasks.any { it.taskId == e.taskId } || e.deviceId == thread.deviceId) loadTasks()
                ServerEvent.Resync -> loadLatest()
                else -> Unit
            }
        }
    }
    // A reconnect may have missed events: catch up with the newest page.
    LaunchedEffect(state.stream) { if (state.stream == StreamStatus.LIVE && settled) { loadLatest(); loadTasks() } }
    // Scrolling to the top loads the page before.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first == 0 && settled && hasMore && !loadingOlder && messages.isNotEmpty()) loadOlder()
        }
    }

    val name = titleOf(thread.peer, thread.last.body)
    val target = view.replyTarget(messages)
    val blocked = if (loading) null else view.replyBlockedReason(thread, messages)
    // Tasks still on their way, and the last finished one for a while.
    val shownTasks = tasks.filter { !it.final } + tasks.filter { it.final }.take(1)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            NyaTopBar(
                name, onBack = onBack,
                subtitle = listOf(thread.peer.takeIf { it != name }, sourceLabel(thread.deviceId, thread.cardNumber, state)).filterNotNull().joinToString(" · "),
            ) {
                if (state.canDelete) IconButton(onClick = { deleteThread = true }) { Icon(Icons.Filled.Delete, contentDescription = "删除会话") }
            }
            error?.let { Box(Modifier.padding(horizontal = 16.dp)) { Banner(Tone.BAD, Icons.Filled.Warning, it) } }
            if (loading) Text("加载中…", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "top") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            loadingOlder -> Text("正在加载更早的消息…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            hasMore -> TextButton(onClick = { scope.launch { loadOlder() } }) { Text("加载更早的消息") }
                            messages.isNotEmpty() -> Text("没有更早的消息了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(messages, key = { it.id }) { m ->
                    RemoteBubble(
                        m,
                        onCopy = { copy(context, "短信", m.body, "已复制") },
                        onMarkUnread = if (m.incoming && m.readAt != null) {
                            {
                                scope.launch {
                                    when (val r = view.markUnread(m.id)) {
                                        is ApiResult.Ok -> {
                                            messages = messages.map { if (it.id == m.id) it.copy(readAt = null) else it }
                                            toast(context, "已标为未读")
                                        }
                                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        onDelete = if (state.canDelete) ({ deleteMessage = m }) else null,
                    )
                }
            }
            for (t in shownTasks) TaskLine(t, onCancel = {
                scope.launch {
                    when (val r = view.cancelTask(t.taskId)) {
                        is ApiResult.Ok -> loadTasks()
                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                    }
                }
            })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (blocked != null) {
                Text(blocked, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (target != null) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("回复（经 ${sourceLabel(target.deviceId, target.cardNumber, state)} 发出）") },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                    )
                    IconButton(
                        enabled = !sending && draft.isNotBlank(),
                        onClick = {
                            sending = true
                            scope.launch {
                                when (val r = view.reply(target.id, draft)) {
                                    is ApiResult.Ok -> {
                                        draft = ""
                                        tasks = listOf(r.value) + tasks.filter { it.taskId != r.value.taskId }
                                    }
                                    is ApiResult.Failure -> toast(context, describeViewFailure(r))
                                }
                                sending = false
                            }
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送") }
                }
            }
        }
    }

    deleteMessage?.let { m ->
        PlatformDeleteDialog(
            title = "删除这条短信？",
            message = "会移到平台回收站，30 天内可以恢复。",
            onDismiss = { deleteMessage = null },
            onConfirm = { phone ->
                deleteMessage = null
                scope.launch {
                    when (val r = view.deleteMessages(listOf(m.id), phone)) {
                        is ApiResult.Ok -> messages = messages.filter { it.id != m.id }
                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                    }
                }
            },
        )
    }
    if (deleteThread) {
        PlatformDeleteDialog(
            title = "删除这个会话？",
            message = "和 ${thread.peer} 的全部短信会移到平台回收站，30 天内可以恢复。",
            onDismiss = { deleteThread = false },
            onConfirm = { phone ->
                deleteThread = false
                scope.launch {
                    when (val r = view.deleteConversations(listOf(thread), phone)) {
                        is ApiResult.Ok -> {
                            toast(context, "已移到平台回收站（${r.value} 条）")
                            onBack()
                        }
                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                    }
                }
            },
        )
    }
}

/** One send task under the conversation: "排队中（等待手机上线）" with 取消, "已送达", "失败：…". */
@Composable
private fun TaskLine(t: OutboundTask, onCancel: () -> Unit) {
    val tone = when (t.status) {
        "failed", "expired" -> Tone.BAD
        "delivered", "sent" -> Tone.OK
        else -> Tone.INFO
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(t.summary, tone)
        Text(
            "  " + t.body,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (t.status == "queued") TextButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
private fun RemoteBubble(m: RemoteMessage, onCopy: () -> Unit, onMarkUnread: (() -> Unit)?, onDelete: (() -> Unit)?) {
    val context = LocalContext.current
    val incoming = m.incoming
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (incoming) Alignment.Start else Alignment.End,
    ) {
        Box {
            Surface(
                color = if (incoming) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                contentColor = if (incoming) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (incoming) 4.dp else 18.dp,
                    bottomEnd = if (incoming) 18.dp else 4.dp,
                ),
                border = if (incoming) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                modifier = Modifier.widthIn(max = 300.dp).combinedClickableCompat(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(m.body, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onCopy() })
                if (onMarkUnread != null) DropdownMenuItem(text = { Text("标为未读") }, onClick = { menu = false; onMarkUnread() })
                if (onDelete != null) DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
        m.code?.let { code ->
            Spacer(Modifier.size(4.dp))
            FilledTonalButton(
                onClick = { copy(context, "验证码", code, "验证码已复制") },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("复制验证码 $code", fontFamily = FontFamily.Monospace) }
        }
        Text(
            shortTime(m.deviceTime) + when {
                incoming && m.readAt == null -> " · 未读"
                !incoming && m.origin == "platform" -> " · 经平台发出"
                !incoming -> " · 手机上发出"
                else -> ""
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (incoming && m.readAt == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The platform recycle bin (needs `delete`): what was deleted from the web, a client or here, kept 30 days. Each entry
 * can be put back, or the receiver phone asked to delete its own copy as well.
 */
@Composable
fun PlatformRecycleBinScreen(runtime: NodeRuntime, onBack: () -> Unit) {
    val view = runtime.view
    val state by view.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RemoteMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(more: Boolean) {
        loading = true
        when (val r = view.recycleBin(before = if (more) items.lastOrNull() else null)) {
            is ApiResult.Ok -> {
                items = if (more) (items + r.value).distinctBy { it.id } else r.value
                hasMore = r.value.size >= ViewApi.PAGE
                error = null
            }
            is ApiResult.Failure -> error = describeViewFailure(r)
        }
        loading = false
    }
    LaunchedEffect(Unit) { load(more = false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            NyaTopBar("平台回收站", onBack = onBack, subtitle = "保留 30 天，可以恢复")
            error?.let { Box(Modifier.padding(horizontal = 16.dp)) { Banner(Tone.BAD, Icons.Filled.Warning, it) } }
            if (!loading && items.isEmpty() && error == null) {
                Text("回收站是空的", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items, key = { it.id }) { m ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Column(
                            Modifier.fillMaxWidth().combinedClickableCompat({ menu = true }).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (m.incoming) "" else "发给 ") + m.peer, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("删除于 " + shortTime(m.deletedAt ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(m.body, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Pill(sourceLabel(m.deviceId, m.cardNumber, state) + " · " + shortTime(m.deviceTime))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("恢复") }, onClick = {
                                menu = false
                                scope.launch {
                                    when (val r = view.restore(m.id)) {
                                        is ApiResult.Ok -> {
                                            items = items.filter { it.id != m.id }
                                            toast(context, "已恢复")
                                        }
                                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                                    }
                                }
                            })
                            DropdownMenuItem(text = { Text("同时删除手机上的原短信") }, onClick = {
                                menu = false
                                scope.launch {
                                    when (val r = view.deletePhoneCopies(listOf(m.id))) {
                                        is ApiResult.Ok -> toast(context, if (r.value.isEmpty()) "这条已经请求过了" else "已请求手机删除")
                                        is ApiResult.Failure -> toast(context, describeViewFailure(r))
                                    }
                                }
                            })
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
                }
                if (hasMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(enabled = !loading, onClick = { scope.launch { load(more = true) } }) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}
