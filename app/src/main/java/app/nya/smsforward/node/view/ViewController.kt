package app.nya.smsforward.node.view

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ConnectionVerdict
import app.nya.smsforward.node.net.FailureKind
import app.nya.smsforward.node.net.ServerUrl
import app.nya.smsforward.node.net.ServerUrlResult
import app.nya.smsforward.node.node.TokenStore
import app.nya.smsforward.node.node.describeFailure
import app.nya.smsforward.node.node.describeUrlProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ViewConnection { NOT_CONNECTED, CONNECTED, NEEDS_LOGIN }

/** Which conversations the 「平台」 tab lists: all, unread only, this phone's, or one receiver phone's. */
sealed interface PlatformFilter {
    data object All : PlatformFilter
    data object Unread : PlatformFilter
    data object ThisPhone : PlatformFilter
    data class Phone(val deviceId: String) : PlatformFilter
}

data class ViewState(
    val enabled: Boolean = false,
    val connection: ViewConnection = ViewConnection.NOT_CONNECTED,
    val serverUrl: String? = null,
    val deviceName: String? = null,
    val scopes: Set<String> = emptySet(),
    val backgroundAlerts: Boolean = true,
    val stream: StreamStatus = StreamStatus.IDLE,
    val conversations: List<RemoteConversation> = emptyList(),
    /** Unread messages on the whole platform (the server's count, for the badge). */
    val unread: Int = 0,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val phones: Map<String, RemotePhone> = emptyMap(),
    val cards: Map<String, RemoteCard> = emptyMap(),
    val query: String = "",
    val filter: PlatformFilter = PlatformFilter.All,
    /** This phone's receiver identity, so its own conversations can be labelled 「本机」. */
    val ownDeviceId: String? = null,
    /**
     * This phone's own messages are in the 「短信」 tab already (the SMS-app feature is on): "全部" leaves them out
     * unless [showOwn]; the 「本机」 chip still lists them.
     */
    val ownInInbox: Boolean = false,
    val showOwn: Boolean = false,
    /** Deleting in the 「短信」 inbox also moves the platform's copy to the platform recycle bin. */
    val deleteOnPlatform: Boolean = false,
) {
    val canReply: Boolean get() = Scopes.REPLY in scopes
    val canSend: Boolean get() = Scopes.SEND in scopes
    val canDelete: Boolean get() = Scopes.DELETE in scopes

    private fun own(c: RemoteConversation) = ownDeviceId != null && c.deviceId == ownDeviceId

    /** What the list shows after the phone filters, which are applied here (search and unread go to the server). */
    val shown: List<RemoteConversation>
        get() = when (val f = filter) {
            PlatformFilter.All, PlatformFilter.Unread -> if (ownInInbox && !showOwn) conversations.filterNot(::own) else conversations
            PlatformFilter.ThisPhone -> conversations.filter(::own)
            is PlatformFilter.Phone -> conversations.filter { it.deviceId == f.deviceId }
        }

    /** How many of this phone's conversations "全部" leaves out (for a hint under the list). */
    val hiddenOwn: Int
        get() = if (ownInInbox && !showOwn && filter == PlatformFilter.All) conversations.count(::own) else 0
}

/** What a connect form submit produced. [field] says which input the message belongs to. */
sealed interface ViewResult {
    data class Ok(val message: String = "") : ViewResult
    data class Error(val message: String, val field: Field? = null) : ViewResult

    enum class Field { URL, CODE, PASSWORD, TOTP }
}

/** Shows and removes the new-message notifications of the 「平台」 tab (PlatformNotifier on Android). */
interface PlatformAlerts {
    fun show(message: RemoteMessage, state: ViewState)

    /** Removes the notification of these conversations ([RemoteMessage.conversationKey]). */
    fun cancel(conversationKeys: Collection<String>)

    object None : PlatformAlerts {
        override fun show(message: RemoteMessage, state: ViewState) = Unit
        override fun cancel(conversationKeys: Collection<String>) = Unit
    }
}

/**
 * Whether a message from the stream deserves a notification: the server says so (new, received, unread, not history),
 * it was not alerted already (a replay after a reconnect), its conversation is not open on screen, and it is not this
 * phone's own message while the 「短信」 inbox alerts about it already.
 */
internal fun shouldAlert(
    event: ServerEvent.Message,
    alreadyAlerted: Boolean,
    foreground: Boolean,
    openConversation: String?,
    ownDeviceId: String?,
    ownAlertedLocally: Boolean,
): Boolean {
    val m = event.message
    if (!event.notify || !m.incoming || alreadyAlerted) return false
    if (foreground && openConversation == m.conversationKey) return false
    if (ownAlertedLocally && ownDeviceId != null && m.deviceId == ownDeviceId) return false
    return true
}

/**
 * The 「查看」 feature: the client identity (pairing code or account login, docs/协议.md §3 / §3.1), the platform's
 * conversations, sending and deleting, and the live event stream: while the app is visible, and with background
 * alerts on also behind it, kept alive by the foreground service. Android-free, so it is tested on the JVM;
 * NodeRuntime wires it up.
 */
class ViewController(
    private val api: ViewApi,
    private val settings: ViewSettings,
    private val tokens: TokenStore,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val defaultName: String,
    /** This phone's receiver deviceId (null when not paired as a receiver). */
    private val ownDeviceId: () -> String?,
    /** The receiver's server address, offered as the default in the connect form. */
    val suggestedServer: () -> String?,
    /** The SMS-app feature is on: this phone's own messages are in the 「短信」 tab. */
    private val ownInInbox: () -> Boolean = { false },
    /** ...and, being the default SMS app, it alerts about them itself. */
    private val ownAlertedLocally: () -> Boolean = { false },
    private val alerts: PlatformAlerts = PlatformAlerts.None,
    /** Whether the background stream is wanted changed: start or stop the foreground service. */
    private val onBackgroundChanged: () -> Unit = {},
) {
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<ViewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)

    /** Every event from the stream, for the open conversation to follow along. */
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    @Volatile
    private var foreground = false

    /** The conversation open on screen ([RemoteConversation.key]), so its messages do not also pop up as alerts. */
    @Volatile
    var openConversation: String? = null
        set(value) {
            field = value
            if (value != null) alerts.cancel(listOf(value))
        }

    private var refreshJob: Job? = null

    /** Message id → conversation, for the alerts shown (to cancel them on "read", and not to show one twice). */
    private val alerted = object : LinkedHashMap<Long, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 500
    }

    private val stream = EventStream(
        api,
        endpoint = { endpoint() },
        onEvent = ::onEvent,
        onConnected = { scheduleRefresh(0) }, // what happened before the stream existed is not replayed
        onStatus = { s ->
            if (s == StreamStatus.TOKEN_DEAD) markTokenDead()
            _state.update { it.copy(stream = s) }
        },
    ).also { it.lastEventId = settings.lastEventId } // a restarted process catches up instead of starting blind

    private fun endpoint(): Pair<String, String>? {
        val url = settings.serverUrl ?: return null
        val token = tokens.read() ?: return null
        return if (settings.needsLogin) null else url to token
    }

    private fun connection(): ViewConnection = when {
        settings.serverUrl == null || settings.deviceId == null -> ViewConnection.NOT_CONNECTED
        settings.needsLogin || tokens.read() == null -> ViewConnection.NEEDS_LOGIN
        else -> ViewConnection.CONNECTED
    }

    private fun snapshot(): ViewState = ViewState(
        enabled = settings.enabled,
        connection = connection(),
        serverUrl = settings.serverUrl,
        deviceName = settings.deviceName,
        scopes = settings.scopes,
        backgroundAlerts = settings.backgroundAlerts,
        ownDeviceId = ownDeviceId(),
        ownInInbox = ownInInbox(),
        showOwn = settings.showOwn,
        deleteOnPlatform = settings.deleteOnPlatform,
    )

    /** Re-reads the settings (and what the other features say) into the state. */
    fun publishSettings() {
        val s = snapshot()
        _state.update {
            it.copy(
                enabled = s.enabled, connection = s.connection, serverUrl = s.serverUrl, deviceName = s.deviceName,
                scopes = s.scopes, backgroundAlerts = s.backgroundAlerts, ownDeviceId = s.ownDeviceId,
                ownInInbox = s.ownInInbox, showOwn = s.showOwn, deleteOnPlatform = s.deleteOnPlatform,
            )
        }
    }

    // --- the switch, the app's lifecycle, the background ---

    fun setEnabled(on: Boolean) {
        settings.enabled = on
        changed()
        if (on) scheduleRefresh(0)
    }

    fun setBackgroundAlerts(on: Boolean) {
        settings.backgroundAlerts = on
        changed()
    }

    fun setShowOwn(on: Boolean) {
        settings.showOwn = on
        publishSettings()
    }

    fun setDeleteOnPlatform(on: Boolean) {
        settings.deleteOnPlatform = on
        publishSettings()
    }

    /** Whether a delete in the 「短信」 inbox should also reach the platform right now. */
    fun deletesOnPlatform(): Boolean =
        settings.deleteOnPlatform && settings.enabled && connection() == ViewConnection.CONNECTED && Scopes.DELETE in settings.scopes

    /** MainActivity's onStart / onStop. Without background alerts the stream only runs while the app is visible. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        publishSettings()
        syncStream()
        if (visible && settings.enabled && connection() == ViewConnection.CONNECTED) {
            stream.nudge()
            scheduleRefresh(0)
        }
    }

    /** Wanted in the background: the foreground service keeps the process (and so the stream) alive for it. */
    fun backgroundWanted(): Boolean = settings.enabled && settings.backgroundAlerts && connection() == ViewConnection.CONNECTED

    /** Brings the stream in line with what is wanted; NodeService also calls it when it starts. */
    fun syncStream() {
        val wanted = settings.enabled && connection() == ViewConnection.CONNECTED && (foreground || settings.backgroundAlerts)
        if (wanted) stream.start(scope) else if (stream.running) stream.stop()
    }

    private fun changed() {
        publishSettings()
        syncStream()
        onBackgroundChanged()
    }

    // --- connecting ---

    suspend fun connectWithCode(rawUrl: String, rawCode: String, rawName: String): ViewResult {
        val url = validUrl(rawUrl) ?: return ViewResult.Error(urlProblem(rawUrl), ViewResult.Field.URL)
        // "483 920" as the console shows it, or typed with full-width digits: keep only the digits.
        val code = rawCode.mapNotNull { c -> Character.digit(c, 10).takeIf { it >= 0 }?.let { '0' + it } }.joinToString("")
        if (code.length != 6) return ViewResult.Error("配对码是 6 位数字", ViewResult.Field.CODE)
        val name = name(rawName)
        return when (val r = api.claim(url, code, name, appVersion)) {
            is ApiResult.Ok -> adopt(url, name, r.value)
            is ApiResult.Failure -> ViewResult.Error(describeViewFailure(r), if (r.code == "invalid_pair_code") ViewResult.Field.CODE else null)
        }
    }

    suspend fun login(rawUrl: String, password: String, totp: String, rawName: String): ViewResult {
        val url = validUrl(rawUrl) ?: return ViewResult.Error(urlProblem(rawUrl), ViewResult.Field.URL)
        if (password.isEmpty()) return ViewResult.Error("请填写管理员密码", ViewResult.Field.PASSWORD)
        val name = name(rawName)
        return when (val r = api.loginDevice(url, password, totp.ifBlank { null }, name, appVersion)) {
            is ApiResult.Ok -> adopt(url, name, r.value)
            is ApiResult.Failure -> ViewResult.Error(
                describeViewFailure(r),
                when (r.code) {
                    "invalid_credentials" -> ViewResult.Field.PASSWORD
                    "totp_required", "invalid_totp" -> ViewResult.Field.TOTP
                    else -> null
                },
            )
        }
    }

    private fun adopt(url: String, name: String, claim: ClientClaim): ViewResult {
        if (stream.running) stream.stop()
        tokens.write(claim.token)
        settings.serverUrl = url
        settings.deviceId = claim.deviceId
        settings.deviceName = name
        settings.scopes = claim.scopes.toSet()
        settings.needsLogin = false
        settings.enabled = true
        // A new identity starts the stream fresh: no replay of another identity's (or server's) history.
        settings.lastEventId = null
        stream.lastEventId = null
        _state.update { it.copy(conversations = emptyList(), loaded = false, error = null) }
        changed()
        scheduleRefresh(0)
        return ViewResult.Ok()
    }

    /** "测试连接": who the server thinks we are; also refreshes the permissions and notices a revoked token. */
    suspend fun test(): ViewResult {
        val (url, token) = endpoint() ?: return ViewResult.Error("还没有登录")
        return when (val r = api.me(url, token)) {
            is ApiResult.Ok -> {
                settings.scopes = r.value.scopes.toSet()
                publishSettings()
                ViewResult.Ok("连接正常 · 服务器 ${r.value.serverVersion ?: "?"} · 令牌有效")
            }
            is ApiResult.Failure -> {
                if (r.verdict == ConnectionVerdict.NEEDS_PAIRING) markTokenDead()
                ViewResult.Error(describeViewFailure(r))
            }
        }
    }

    /** Forgets the client token here. The device stays in the console until it is deleted there. */
    fun disconnect() {
        stream.stop()
        tokens.clear()
        settings.deviceId = null
        settings.scopes = emptySet()
        settings.needsLogin = false
        settings.lastEventId = null
        alerts.cancel(synchronized(alerted) { alerted.values.toSet() })
        _state.update { it.copy(conversations = emptyList(), unread = 0, loaded = false, error = null, phones = emptyMap(), cards = emptyMap()) }
        changed()
    }

    private fun markTokenDead() {
        settings.needsLogin = true
        tokens.clear()
        publishSettings()
        onBackgroundChanged()
    }

    // --- the list ---

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        scheduleRefresh(SEARCH_DEBOUNCE_MS)
    }

    fun setFilter(filter: PlatformFilter) {
        val unreadChanged = (filter == PlatformFilter.Unread) != (_state.value.filter == PlatformFilter.Unread)
        _state.update { it.copy(filter = filter) }
        if (unreadChanged) scheduleRefresh(0)
    }

    fun refresh() = scheduleRefresh(0)

    @Synchronized // called from the UI and from the stream's thread
    private fun scheduleRefresh(afterMs: Long) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (afterMs > 0) delay(afterMs)
            load()
        }
    }

    /** Fetches the conversations, and the phones and cards that label them. */
    suspend fun load() {
        val (url, token) = endpoint() ?: return
        if (!settings.enabled) return
        val s = _state.value
        _state.update { it.copy(loading = true, ownInInbox = ownInInbox(), ownDeviceId = ownDeviceId()) }
        when (val page = api.conversations(url, token, query = s.query.trim().ifEmpty { null }, unreadOnly = s.filter == PlatformFilter.Unread)) {
            is ApiResult.Ok -> _state.update { it.copy(conversations = page.value.items, unread = page.value.unread, loaded = true, loading = false, error = null) }
            is ApiResult.Failure -> {
                if (page.verdict == ConnectionVerdict.NEEDS_PAIRING) markTokenDead()
                _state.update { it.copy(loading = false, error = describeViewFailure(page)) }
                return
            }
        }
        (api.phones(url, token) as? ApiResult.Ok)?.let { r -> _state.update { it.copy(phones = r.value.associateBy { p -> p.id }) } }
        (api.cards(url, token) as? ApiResult.Ok)?.let { r -> _state.update { it.copy(cards = r.value.associateBy { c -> c.number }) } }
    }

    // --- one conversation ---

    suspend fun loadThread(thread: RemoteConversation, before: Long? = null): ApiResult<List<RemoteMessage>> = authed { url, token ->
        api.messages(url, token, thread, before).map { page ->
            page.items.sortedWith(compareBy<RemoteMessage> { it.deviceTime }.thenBy { it.id })
        }
    }

    suspend fun markThreadRead(thread: RemoteConversation): ApiResult<List<Long>> = authed { url, token ->
        api.markThreadRead(url, token, thread).also { r ->
            alerts.cancel(listOf(thread.key))
            if (r is ApiResult.Ok && r.value.isNotEmpty()) scheduleRefresh(0)
        }
    }

    /** The notification's "标为已读": the messages it showed. */
    suspend fun markRead(ids: List<Long>): ApiResult<Unit> = authed { url, token -> api.markRead(url, token, ids) }

    suspend fun markUnread(id: Long): ApiResult<Unit> = authed { url, token ->
        api.markUnread(url, token, id).also { if (it is ApiResult.Ok) scheduleRefresh(0) }
    }

    // --- sending ---

    /** The message a reply in [thread] answers: its newest received one. */
    fun replyTarget(messages: List<RemoteMessage>): RemoteMessage? = messages.lastOrNull { it.incoming }

    /** Why [thread] cannot be replied to right now, or null. */
    fun replyBlockedReason(thread: RemoteConversation, messages: List<RemoteMessage>): String? {
        val s = _state.value
        return when {
            !s.canReply -> "这个客户端没有“回复”权限，可以在 Web 的设备页调整"
            !thread.replyable -> "字母 / 名称发件人无法回复"
            replyTarget(messages) == null -> "这个会话里没有收到过短信，无法回复（可以用「新短信」）"
            else -> s.phones[replyTarget(messages)!!.deviceId]?.blockedReason(reply = true)
        }
    }

    suspend fun reply(messageId: Long, body: String): ApiResult<OutboundTask> = authed { url, token -> api.reply(url, token, messageId, body.trim()) }

    suspend fun sendNew(deviceId: String, simSlot: Int?, to: String, body: String): ApiResult<OutboundTask> =
        authed { url, token -> api.sendNew(url, token, deviceId, simSlot, to.trim(), body.trim()) }

    suspend fun tasks(thread: RemoteConversation): ApiResult<List<OutboundTask>> =
        authed { url, token -> api.outbound(url, token, thread.deviceId, thread.peerKey) }

    suspend fun cancelTask(taskId: String): ApiResult<OutboundTask> = authed { url, token -> api.cancelOutbound(url, token, taskId) }

    // --- deleting ---

    suspend fun deleteMessages(ids: List<Long>, deletePhone: Boolean): ApiResult<List<Long>> = authed { url, token ->
        api.deleteMessages(url, token, ids, deletePhone).also { if (it is ApiResult.Ok) scheduleRefresh(0) }
    }

    suspend fun deleteConversations(threads: List<RemoteConversation>, deletePhone: Boolean): ApiResult<Int> = authed { url, token ->
        api.deleteConversations(url, token, threads, deletePhone).also {
            if (it is ApiResult.Ok) {
                alerts.cancel(threads.map { t -> t.key })
                scheduleRefresh(0)
            }
        }
    }

    suspend fun recycleBin(before: RemoteMessage? = null): ApiResult<List<RemoteMessage>> =
        authed { url, token -> api.deletedMessages(url, token, before?.id, before?.deletedAt) }

    suspend fun restore(id: Long): ApiResult<Unit> = authed { url, token ->
        api.restoreMessage(url, token, id).also { if (it is ApiResult.Ok) scheduleRefresh(0) }
    }

    suspend fun deletePhoneCopies(ids: List<Long>): ApiResult<List<Long>> = authed { url, token -> api.deletePhoneCopies(url, token, ids) }

    /** Runs [call] with the endpoint, and notices a dead token in its answer. */
    private suspend fun <T> authed(call: suspend (String, String) -> ApiResult<T>): ApiResult<T> {
        val (url, token) = endpoint()
            ?: return ApiResult.Failure(ConnectionVerdict.TRANSIENT, FailureKind.NETWORK, code = "not_connected", message = "还没有登录")
        val r = call(url, token)
        if (r is ApiResult.Failure && r.verdict == ConnectionVerdict.NEEDS_PAIRING) markTokenDead()
        return r
    }

    // --- events ---

    private fun onEvent(event: ServerEvent) {
        stream.lastEventId?.let { if (it != settings.lastEventId) settings.lastEventId = it }
        _events.tryEmit(event)
        when (event) {
            is ServerEvent.Message -> {
                maybeAlert(event)
                scheduleRefresh(EVENT_DEBOUNCE_MS)
            }
            is ServerEvent.Read -> {
                val keys = synchronized(alerted) { event.ids.mapNotNull { alerted[it] }.toSet() }
                if (keys.isNotEmpty()) alerts.cancel(keys)
                scheduleRefresh(EVENT_DEBOUNCE_MS)
            }
            is ServerEvent.Deleted, ServerEvent.Resync -> scheduleRefresh(EVENT_DEBOUNCE_MS)
            is ServerEvent.Device -> _state.update { s ->
                val phone = s.phones[event.deviceId] ?: return@update s
                s.copy(phones = s.phones + (event.deviceId to phone.copy(online = event.online, battery = event.battery ?: phone.battery)))
            }
            else -> Unit
        }
    }

    private fun maybeAlert(event: ServerEvent.Message) {
        val m = event.message
        val already = synchronized(alerted) { m.id in alerted }
        if (!shouldAlert(event, already, foreground, openConversation, ownDeviceId(), ownAlertedLocally())) return
        synchronized(alerted) { alerted[m.id] = m.conversationKey }
        alerts.show(m, _state.value)
    }

    // --- helpers ---

    private fun validUrl(raw: String): String? = (ServerUrl.validate(raw) as? ServerUrlResult.Ok)?.url

    private fun urlProblem(raw: String): String = when (val r = ServerUrl.validate(raw)) {
        is ServerUrlResult.Invalid -> describeUrlProblem(r.reason)
        is ServerUrlResult.Ok -> ""
    }

    private fun name(raw: String) = raw.trim().ifEmpty { defaultName }.take(60)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val EVENT_DEBOUNCE_MS = 300L
    }
}

/** Words for a failed client request; the server's own codes first (same wording as the Flutter client). */
fun describeViewFailure(f: ApiResult.Failure): String = when (f.code) {
    "invalid_credentials" -> "密码不正确"
    "totp_required" -> "服务器已开启二次验证，请填写动态验证码"
    "invalid_totp" -> "动态验证码不正确，请核对手机时间后重试"
    "totp_unavailable" -> "服务器无法读取二次验证密钥，请检查服务端配置"
    "too_many_attempts" -> "尝试次数太多，请稍后再试"
    "setup_required" -> "服务器还没有完成初始设置，请先在网页里创建管理员"
    "invalid_pair_code" -> "配对码错误、已过期或已被使用"
    "pair_kind_mismatch" -> "这是接收端的配对码。查看需要在 Web「设备与客户端」里选择“客户端”来生成配对码"
    "scope_denied" -> "这个客户端没有该操作的权限，可以在 Web 的设备页调整"
    "message_not_found" -> "短信已不存在"
    "pair_code_required" -> "这个服务器不允许用账号登录，请改用配对码"
    "policy_denied" -> "这台手机的下发策略不允许（先在 Web 里调整平台策略，手机上也要开启）"
    "reply_not_supported" -> "字母 / 名称发件人无法回复"
    "rate_limited" -> "发送太频繁，请稍后再试"
    "bad_body" -> "短信内容不能为空，且不超过 1000 个字"
    "bad_recipient" -> "收件人号码格式不对"
    "bad_sim_slot" -> "这台手机没有这个 SIM 卡槽"
    "device_revoked" -> "这台手机的令牌已被吊销，需要先重新配对"
    "not_cancellable" -> "任务已经交给手机，无法取消"
    "not_connected" -> "还没有登录"
    else -> if (f.verdict == ConnectionVerdict.NEEDS_PAIRING) "登录已失效，需要重新登录" else describeFailure(f)
}
