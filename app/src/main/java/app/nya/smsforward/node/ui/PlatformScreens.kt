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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nya.smsforward.node.inbox.ContactNames
import app.nya.smsforward.node.inbox.senderBrand
import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.view.PlatformFilter
import app.nya.smsforward.node.view.RemoteConversation
import app.nya.smsforward.node.view.RemoteMessage
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
 * The 「平台」 tab: every conversation on the platform, from every receiver phone, newest first. Search and "未读" are
 * asked of the server; the phone chips filter what came back. Updates live while the app is open.
 */
@Composable
fun PlatformScreen(runtime: NodeRuntime, onOpen: (RemoteConversation) -> Unit, onOpenSettings: () -> Unit) {
    val view = runtime.view
    val state by view.state.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { view.refresh() }

    Column(Modifier.fillMaxSize()) {
        if (searching) {
            SearchBar(state.query, view::setQuery, onClose = { searching = false; view.setQuery("") }, placeholder = "搜索平台上的短信或号码")
        } else {
            NyaTopBar("平台", subtitle = if (state.unread > 0) "${state.unread} 条未读" else null) {
                if (state.connection == ViewConnection.CONNECTED) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                    IconButton(onClick = { view.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
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
            return
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
                    else -> "平台上还没有短信"
                },
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.key }) { c -> PlatformConversationRow(c, state, onClick = { onOpen(c) }) }
        }
    }
}

@Composable
private fun PlatformConversationRow(c: RemoteConversation, state: ViewState, onClick: () -> Unit) {
    val name = titleOf(c.peer, c.last.body)
    Row(
        modifier = Modifier.fillMaxWidth().combinedClickableCompat(onClick).padding(horizontal = 16.dp, vertical = 12.dp),
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
    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

/**
 * One platform conversation. Opening it marks it read on the server; older messages load when scrolling to the top.
 * New messages, reads and deletions from the event stream show up while it is open.
 */
@Composable
fun PlatformThreadScreen(runtime: NodeRuntime, thread: RemoteConversation, onBack: () -> Unit) {
    val view = runtime.view
    val state by view.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<RemoteMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var settled by remember { mutableStateOf(false) } // scrolled to the newest message after the first load
    var loadingOlder by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun sorted(list: List<RemoteMessage>) = list.distinctBy { it.id }.sortedWith(compareBy<RemoteMessage> { it.deviceTime }.thenBy { it.id })

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
                ServerEvent.Resync -> loadLatest()
                else -> Unit
            }
        }
    }
    // A reconnect may have missed events: catch up with the newest page.
    LaunchedEffect(state.stream) { if (state.stream == StreamStatus.LIVE && settled) loadLatest() }
    // Scrolling to the top loads the page before.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first == 0 && settled && hasMore && !loadingOlder && messages.isNotEmpty()) loadOlder()
        }
    }

    val name = titleOf(thread.peer, thread.last.body)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            NyaTopBar(name, onBack = onBack, subtitle = listOf(thread.peer.takeIf { it != name }, sourceLabel(thread.deviceId, thread.cardNumber, state)).filterNotNull().joinToString(" · "))
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
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteBubble(m: RemoteMessage, onCopy: () -> Unit, onMarkUnread: (() -> Unit)?) {
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
