package app.nya.smsforward.node.node

import android.content.Context
import android.os.Build
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.data.AndroidSqlDb
import app.nya.smsforward.node.data.KeystoreTokenStore
import app.nya.smsforward.node.data.Outbox
import app.nya.smsforward.node.data.PrefsSettings
import app.nya.smsforward.node.data.RecentItem
import app.nya.smsforward.node.data.SqliteOutbox
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.sms.SimSlots
import app.nya.smsforward.node.work.UploadScheduler
import app.nya.smsforward.node.work.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val outbox: Outbox by lazy { SqliteOutbox(AndroidSqlDb(app)) }
    private val api by lazy { OkHttpNodeApi() }
    val incoming by lazy { IncomingHandler(outbox, settings, System::currentTimeMillis) }
    val uploader by lazy { Uploader(outbox, api, settings, tokens, System::currentTimeMillis) }
    val pairing by lazy {
        PairingManager(
            api, settings, tokens,
            appVersion = BuildConfig.VERSION_NAME,
            defaultDeviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "接收端手机",
            sims = { SimSlots.activeSims(app) },
            onPaired = {
                Notifier.clearNeedsPairing(app)
                UploadScheduler.enqueue(app)
            },
        )
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
