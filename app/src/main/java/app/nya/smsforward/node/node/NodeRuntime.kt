package app.nya.smsforward.node.node

import android.content.Context
import android.os.Build
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.data.AndroidSqlDb
import app.nya.smsforward.node.data.KeystoreTokenStore
import app.nya.smsforward.node.data.LedgerEntry
import app.nya.smsforward.node.data.Outbox
import app.nya.smsforward.node.data.PrefsSettings
import app.nya.smsforward.node.data.RecentItem
import app.nya.smsforward.node.data.SendLedger
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.data.SmsTrash
import app.nya.smsforward.node.data.SqliteSendLedger
import app.nya.smsforward.node.inbox.Recycler
import app.nya.smsforward.node.net.ChannelState
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.policy.SendPolicy
import app.nya.smsforward.node.send.AndroidSmsSender
import app.nya.smsforward.node.send.SendCoordinator
import app.nya.smsforward.node.send.SendGate
import app.nya.smsforward.node.send.SendStatusTracker
import app.nya.smsforward.node.service.NodeService
import app.nya.smsforward.node.service.SendChannel
import app.nya.smsforward.node.sms.AndroidSmsBox
import app.nya.smsforward.node.sms.SentSync
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.SimSlots
import app.nya.smsforward.node.work.SentSyncScheduler
import app.nya.smsforward.node.work.UploadScheduler
import app.nya.smsforward.node.work.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionKind {
    /** Never paired, or disconnected on purpose. SMS are not collected. */
    NOT_PAIRED,

    /** The server rejected the token (or it was lost). SMS keep queueing; uploads wait for re-pairing. */
    NEEDS_PAIRING,

    PAIRED,
}

data class UiState(
    val connection: ConnectionKind = ConnectionKind.NOT_PAIRED,
    val serverUrl: String? = null,
    val deviceName: String? = null,
    val pending: Int = 0,
    val lastUploadAt: Long = 0,
    val lastError: String? = null,
    val recent: List<RecentItem> = emptyList(),
    val sendPolicy: SendPolicy = SendPolicy.OFF,
    val sendLimitPerHour: Int = 10,
    val allowedRecipients: Set<String> = emptySet(),
    val channel: ChannelState = ChannelState.Idle,
    val sendTasks: List<LedgerEntry> = emptyList(),
    val syncSent: Boolean = false,
    val backfillDays: Int = 0,
    /** The SIM inventory Android currently exposes to this app; shown locally for diagnosis. */
    val sims: List<SimInfo> = emptyList(),
    val manualSimNumbers: Map<Int, String> = emptyMap(),
)

/**
 * The app's object graph. One instance per process, shared by the UI, the SMS receiver and the upload worker (all three
 * can be the first to start the process, so nothing here may assume the UI exists).
 *
 * Anything that touches SQLite is created lazily and used off the main thread.
 */
class NodeRuntime private constructor(private val app: Context) {
    val settings = PrefsSettings(app)
    val tokens = KeystoreTokenStore(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One database handle for everything that lives in node.db.
    private val sqlDb by lazy { AndroidSqlDb(app) }
    val outbox: Outbox by lazy { SqliteOutbox(sqlDb) }
    val ledger: SendLedger by lazy { SqliteSendLedger(sqlDb) }
    private val api by lazy { OkHttpNodeApi() }
    val incoming by lazy { IncomingHandler(outbox, settings, System::currentTimeMillis) }
    val uploader by lazy { Uploader(outbox, api, settings, tokens, System::currentTimeMillis) }
    val pairing by lazy {
        PairingManager(
            api, settings, tokens,
            appVersion = BuildConfig.VERSION_NAME,
            defaultDeviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "接收端手机",
            sims = { SimSlots.activeSims(app, settings.manualSimNumbers) },
            onPaired = {
                Notifier.clearNeedsPairing(app)
                UploadScheduler.enqueue(app)
                NodeService.sync(app) // a re-paired phone with sending switched on reconnects its channel
            },
        )
    }

    // --- sending (M3) ---
    private val _channelState = MutableStateFlow<ChannelState>(ChannelState.Idle)
    val sender by lazy { AndroidSmsSender(app) { settings.manualSimNumbers } }
    private val tracker = SendStatusTracker()
    val coordinator: SendCoordinator by lazy {
        SendCoordinator(
            SendGate(settings, outbox, ledger, System::currentTimeMillis), ledger, sender, tracker, System::currentTimeMillis,
            emit = { channel.emit(it) },
        )
    }
    val channel: SendChannel by lazy {
        SendChannel(app, scope, settings, tokens, sender, { smsBox }, { coordinator }, _channelState, BuildConfig.VERSION_NAME)
    }

    /**
     * Changes what the platform may make this phone send. Turning it off tells the server first (so the console shows
     * "off" instead of a phone that merely vanished) and then stops the foreground service.
     */
    fun applySendPolicy(policy: SendPolicy) {
        settings.sendPolicy = policy
        channel.sendHello()
        scope.launch {
            if (policy == SendPolicy.OFF) delay(1_500) // let the hello go out before the connection closes
            NodeService.sync(app)
            refresh()
        }
    }

    fun applySendLimit(perHour: Int) {
        settings.sendLimitPerHour = perHour
        scope.launch { refresh() }
    }

    fun applyAllowedRecipients(recipients: Set<String>) {
        settings.allowedRecipients = recipients
        scope.launch { refresh() }
    }

    /** Saves a user-supplied line number for a slot and reports it on the next phone hello. */
    fun setManualSimNumber(slot: Int, raw: String): String? {
        val value = raw.trim()
        if (slot !in 1..15) return "SIM 槽位不正确"
        if (value.isNotEmpty() && (!PeerKey.isReplyable(value) || value.none(Char::isDigit))) {
            return "请输入电话号码，只能包含数字、+、空格、短横线或括号"
        }
        val next = settings.manualSimNumbers.toMutableMap()
        if (value.isEmpty()) next.remove(slot) else next[slot] = PeerKey.normalize(value)
        settings.manualSimNumbers = next
        channel.sendHello()
        scope.launch {
            NodeService.sync(app)
            refresh()
        }
        return null
    }

    // --- sent messages and history (M4) ---
    // Full edition: a platform deletion goes to the phone's recycle bin (30 days) instead of being final.
    val smsTrash by lazy { SmsTrash(sqlDb) }
    val recycler by lazy { Recycler(app, smsTrash) }
    val smsBox by lazy {
        AndroidSmsBox(
            app,
            sims = { SimSlots.activeSims(app, settings.manualSimNumbers) },
            recycle = if (BuildConfig.FULL_EDITION) { id -> recycler.recycle(id, Recycler.ORIGIN_PLATFORM) } else null,
        )
    }
    val sentSync by lazy { SentSync(settings, smsBox, outbox, ledger, System::currentTimeMillis) }

    /** Switches "sync sent messages" on or off. Turning it on starts a fresh cursor at the current end of the sent box. */
    fun applySyncSent(enabled: Boolean) {
        settings.syncSent = enabled
        if (enabled) {
            settings.sentCursor = -1 // noted by the first pass: only messages sent from now on
            SentSyncScheduler.enable(app)
        } else {
            SentSyncScheduler.disable(app)
        }
        channel.sendHello()
        scope.launch { refresh() }
    }

    /** History to report once when sync is on: 0 (none), 7 or 30 days. */
    fun applyBackfillDays(days: Int) {
        settings.backfillDays = days
        if (settings.syncSent) SentSyncScheduler.requestSoon(app)
        channel.sendHello()
        scope.launch { refresh() }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Recomputes what the screens show. Safe to call from anywhere; the database is read on the IO dispatcher. */
    suspend fun refresh() {
        val next = withContext(Dispatchers.IO) {
            val paired = settings.serverUrl != null && settings.deviceId != null
            UiState(
                connection = when {
                    !paired -> ConnectionKind.NOT_PAIRED
                    settings.needsPairing || tokens.read() == null -> ConnectionKind.NEEDS_PAIRING
                    else -> ConnectionKind.PAIRED
                },
                serverUrl = settings.serverUrl,
                deviceName = settings.deviceName,
                pending = outbox.pendingCount(),
                lastUploadAt = settings.lastUploadAt,
                lastError = settings.lastError,
                recent = outbox.recent(RECENT_COUNT),
                sendPolicy = settings.sendPolicy,
                sendLimitPerHour = settings.sendLimitPerHour,
                allowedRecipients = settings.allowedRecipients,
                channel = _channelState.value,
                sendTasks = ledger.recent(5),
                syncSent = settings.syncSent,
                backfillDays = settings.backfillDays,
                sims = SimSlots.activeSims(app, settings.manualSimNumbers),
                manualSimNumbers = settings.manualSimNumbers,
            )
        }
        _state.value = next
    }

    companion object {
        private const val RECENT_COUNT = 15

        @Volatile
        private var instance: NodeRuntime? = null

        fun get(context: Context): NodeRuntime =
            instance ?: synchronized(this) { instance ?: NodeRuntime(context.applicationContext).also { instance = it } }
    }
}
