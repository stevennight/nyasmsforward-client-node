package app.nya.smsforward.node.view

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.ConnectionVerdict
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
) {
    /** What the list shows after the phone filters, which are applied here (search and unread go to the server). */
    val shown: List<RemoteConversation>
        get() = when (val f = filter) {
            PlatformFilter.All, PlatformFilter.Unread -> conversations
            PlatformFilter.ThisPhone -> conversations.filter { it.deviceId == ownDeviceId }
            is PlatformFilter.Phone -> conversations.filter { it.deviceId == f.deviceId }
        }
}

/** What a connect form submit produced. [field] says which input the message belongs to. */
sealed interface ViewResult {
    data class Ok(val message: String = "") : ViewResult
    data class Error(val message: String, val field: Field? = null) : ViewResult

    enum class Field { URL, CODE, PASSWORD, TOTP }
}

/**
 * The 「查看」 feature: the client identity (pairing code or account login, docs/协议.md §3 / §3.1), the platform's
 * conversation list, and the live event stream while the app is in the foreground. Android-free, so it is tested on
 * the JVM; NodeRuntime wires it up.
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
) {
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<ViewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)

    /** Every event from the stream, for the open conversation to follow along. */
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private var foreground = false
    private var refreshJob: Job? = null

    private val stream = EventStream(
        api,
        endpoint = { endpoint() },
        onEvent = ::onEvent,
        onConnected = { scheduleRefresh(0) }, // what happened before the stream existed is not replayed
        onStatus = { s ->
            if (s == StreamStatus.TOKEN_DEAD) markTokenDead()
            _state.update { it.copy(stream = s) }
        },
    )

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
        ownDeviceId = ownDeviceId(),
    )

    private fun publishSettings() {
        val s = snapshot()
        _state.update { it.copy(enabled = s.enabled, connection = s.connection, serverUrl = s.serverUrl, deviceName = s.deviceName, scopes = s.scopes, ownDeviceId = s.ownDeviceId) }
    }

    // --- the switch and the app's lifecycle ---

    fun setEnabled(on: Boolean) {
        settings.enabled = on
        publishSettings()
        syncStream()
        if (on) scheduleRefresh(0)
    }

    /** MainActivity's onStart / onStop: the stream only runs while the app is visible (background alerts come later). */
    fun setForeground(visible: Boolean) {
        foreground = visible
        publishSettings()
        syncStream()
        if (visible && settings.enabled && connection() == ViewConnection.CONNECTED) {
            stream.nudge()
            scheduleRefresh(0)
        }
    }

    private fun syncStream() {
        val wanted = foreground && settings.enabled && connection() == ViewConnection.CONNECTED
        if (wanted) stream.start(scope) else if (stream.running) stream.stop()
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
        tokens.write(claim.token)
        settings.serverUrl = url
        settings.deviceId = claim.deviceId
        settings.deviceName = name
        settings.scopes = claim.scopes.toSet()
        settings.needsLogin = false
        settings.enabled = true
        _state.update { it.copy(conversations = emptyList(), loaded = false, error = null) }
        publishSettings()
        syncStream()
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
        _state.update { it.copy(conversations = emptyList(), unread = 0, loaded = false, error = null, phones = emptyMap(), cards = emptyMap()) }
        publishSettings()
    }

    private fun markTokenDead() {
        settings.needsLogin = true
        tokens.clear()
        publishSettings()
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
        _state.update { it.copy(loading = true) }
        val page = api.conversations(url, token, query = s.query.trim().ifEmpty { null }, unreadOnly = s.filter == PlatformFilter.Unread)
        when (page) {
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

    suspend fun loadThread(thread: RemoteConversation, before: Long? = null): ApiResult<List<RemoteMessage>> {
        val (url, token) = endpoint() ?: return notConnected()
        return api.messages(url, token, thread, before).map { page ->
            page.items.sortedWith(compareBy<RemoteMessage> { it.deviceTime }.thenBy { it.id })
        }
    }

    suspend fun markThreadRead(thread: RemoteConversation): ApiResult<List<Long>> {
        val (url, token) = endpoint() ?: return notConnected()
        val r = api.markThreadRead(url, token, thread)
        if (r is ApiResult.Ok && r.value.isNotEmpty()) scheduleRefresh(0)
        return r
    }

    suspend fun markUnread(id: Long): ApiResult<Unit> {
        val (url, token) = endpoint() ?: return notConnected()
        val r = api.markUnread(url, token, id)
        if (r is ApiResult.Ok) scheduleRefresh(0)
        return r
    }

    private fun notConnected() = ApiResult.Failure(ConnectionVerdict.TRANSIENT, app.nya.smsforward.node.net.FailureKind.NETWORK, code = "not_connected", message = "还没有登录")

    // --- events ---

    private fun onEvent(event: ServerEvent) {
        _events.tryEmit(event)
        when (event) {
            is ServerEvent.Message, is ServerEvent.Read, is ServerEvent.Deleted, ServerEvent.Resync -> scheduleRefresh(EVENT_DEBOUNCE_MS)
            is ServerEvent.Device -> _state.update { s ->
                val phone = s.phones[event.deviceId] ?: return@update s
                s.copy(phones = s.phones + (event.deviceId to phone.copy(online = event.online, battery = event.battery ?: phone.battery)))
            }
            else -> Unit
        }
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
    else -> if (f.verdict == ConnectionVerdict.NEEDS_PAIRING) "登录已失效，需要重新登录" else describeFailure(f)
}
