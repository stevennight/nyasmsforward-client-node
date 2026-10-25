package app.nya.smsforward.node.view

import app.nya.smsforward.node.net.ApiResult
import app.nya.smsforward.node.net.Backoff
import app.nya.smsforward.node.net.ConnectionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import java.io.IOException

enum class StreamStatus {
    IDLE,
    CONNECTING,
    LIVE,

    /** Disconnected, retrying. The token is kept: only an explicit "token is dead" answer signs the client out. */
    WAITING,

    /** The server said the client token is dead: the stream stopped; log in again. */
    TOKEN_DEAD,

    /** The token is fine but lacks the `read` permission: nothing to stream. */
    FORBIDDEN,
}

/**
 * Keeps GET /api/v1/events open (docs/协议.md §6.1): connects, hands every event to [onEvent], reconnects forever with
 * [Backoff]. A reconnect sends `Last-Event-ID`, so the server replays what was missed meanwhile (or sends "resync").
 * [onConnected] runs on every (re)connection, before events flow, so the owner can fetch what happened while there
 * was no stream (a first connection is not replayed).
 *
 * [endpoint] gives (server address, client token), or null when there is nothing to connect to.
 */
class EventStream(
    private val api: ViewApi,
    private val endpoint: () -> Pair<String, String>?,
    private val onEvent: (ServerEvent) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onStatus: (StreamStatus) -> Unit = {},
    private val random: () -> Double = Math::random,
    private val now: () -> Long = System::currentTimeMillis,
    /** Waits out a backoff delay; the default can be cut short by [nudge]. Tests pass one that returns at once. */
    pause: (suspend (Long) -> Unit)? = null,
) {
    /** The id of the last event seen; seeded by the owner so a new process resumes where the old one stopped. */
    @Volatile
    var lastEventId: String? = null

    @Volatile
    var status: StreamStatus = StreamStatus.IDLE
        private set

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pause: suspend (Long) -> Unit = pause ?: { ms -> withTimeoutOrNull(ms) { wake.receive() } }
    private var job: Job? = null

    @Volatile
    private var open: OpenStream? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        // The read below blocks in a socket; cancelling the call is what ends it (the reader then closes the body).
        open?.cancel()
        set(StreamStatus.IDLE)
    }

    val running: Boolean get() = job?.isActive == true

    /** Reconnects now instead of waiting out the backoff (the app came to the foreground, the network came back). */
    fun nudge() {
        wake.trySend(Unit)
    }

    /** Runs until cancelled, until [endpoint] has nothing, or until the token is rejected. */
    suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val (url, token) = endpoint() ?: run {
                set(StreamStatus.IDLE)
                return
            }
            set(StreamStatus.CONNECTING)
            var upFor = 0L
            when (val r = api.openEvents(url, token, lastEventId)) {
                is ApiResult.Ok -> {
                    val opened = now()
                    open = r.value
                    set(StreamStatus.LIVE)
                    try {
                        onConnected()
                        consume(r.value.response)
                    } catch (_: IOException) {
                        // dropped connection or silence timeout: reconnect below
                    } finally {
                        r.value.close()
                        open = null
                        upFor = now() - opened
                    }
                }
                is ApiResult.Failure -> {
                    if (r.verdict == ConnectionVerdict.NEEDS_PAIRING) {
                        set(StreamStatus.TOKEN_DEAD)
                        return
                    }
                    if (r.code == "scope_denied") {
                        set(StreamStatus.FORBIDDEN)
                        return
                    }
                }
            }
            if (!currentCoroutineContext().isActive) break
            // A connection that held for a while was healthy: start the backoff over. One that dropped at once was not.
            if (upFor > HEALTHY_MS) attempt = 0
            set(StreamStatus.WAITING)
            pause(Backoff.delayMillis(attempt++, random()))
        }
    }

    private fun consume(response: Response) {
        val source = response.body?.source() ?: return
        val parser = SseParser()
        while (true) {
            val line = source.readUtf8Line() ?: return // the server closed the stream
            val raw = parser.feed(line) ?: continue
            raw.id?.let { lastEventId = it }
            onEvent(ServerEvent.parse(raw))
        }
    }

    private fun set(s: StreamStatus) {
        if (status == s) return
        status = s
        onStatus(s)
    }

    private companion object {
        const val HEALTHY_MS = 30_000L
    }
}
