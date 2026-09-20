package app.nya.smsforward.node.net

import app.nya.smsforward.node.send.SendTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Where the send channel stands, for the status screen. */
sealed interface ChannelState {
    /** Not running (sending is off, or the phone is not paired). */
    data object Idle : ChannelState

    data object Connecting : ChannelState
    data object Connected : ChannelState

    /** Disconnected; trying again in [retryInMs]. The token is kept: only an explicit "token is dead" answer unpairs. */
    data class Waiting(val reason: String, val retryInMs: Long) : ChannelState

    /** The server said the token is dead. The channel stops until the phone is paired again. */
    data object NeedsPairing : ChannelState
}

class SocketConfig(val baseUrl: String, val token: String)

/** What the channel does with what it hears. Called on OkHttp's reader thread: hand slow work to another thread. */
interface SocketHandler {
    /** The connection is up and hello has been sent: a good moment to replay receipts. */
    fun onOpen()

    fun onSendSms(task: SendTask)
}

/** Why [NodeSocket.run] returned. */
enum class SocketEnd { NEEDS_PAIRING, STOPPED }

/**
 * The phone's long-lived WebSocket to the server (docs/协议.md §6.2): stays connected, reports its state, receives send
 * tasks and carries the receipts back.
 *
 * It reconnects forever with exponential backoff (1 s to 5 min, jittered) and only gives up on an explicit "this token
 * is dead" answer, exactly like uploads do (docs/协议.md §2.2): a flaky network, a proxy restart or a rebooting server
 * must never unpair the phone.
 */
class NodeSocket(
    private val client: OkHttpClient,
    /** The current address and token, read again for every attempt so a changed address or a fresh token is picked up. */
    private val config: () -> SocketConfig?,
    private val hello: () -> HelloFrame,
    private val handler: SocketHandler,
    private val onState: (ChannelState) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Double = Math::random,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    @Volatile
    private var current: WebSocket? = null

    /** Sends a text frame if connected. Best effort: receipts that get lost are replayed after the next reconnect. */
    fun send(text: String): Boolean = current?.send(text) ?: false

    /** Reports the phone's state now instead of at the next heartbeat (e.g. right after the policy changed). */
    fun sendHello(): Boolean = send(Frames.hello(hello()))

    /** Runs until the token is rejected, the config disappears, or the calling coroutine is cancelled. */
    suspend fun run(): SocketEnd {
        var attempt = 0
        while (true) {
            val cfg = config() ?: return SocketEnd.STOPPED
            onState(ChannelState.Connecting)
            val end = connectOnce(cfg)

            val verdict = Connection.classify(end.httpStatus, end.errorCode ?: end.reason, end.closeCode)
            if (verdict == ConnectionVerdict.NEEDS_PAIRING) {
                onState(ChannelState.NeedsPairing)
                return SocketEnd.NEEDS_PAIRING
            }

            // A connection that held for a while was healthy: start the backoff over. One that dropped at once was not.
            if (end.upForMs >= STABLE_MS) attempt = 0
            val wait = Backoff.delayMillis(attempt++, random())
            onState(ChannelState.Waiting(reasonFor(verdict, end), wait))
            pause(wait)
        }
    }

    private class Attempt(
        val upForMs: Long,
        val closeCode: Int? = null,
        val reason: String? = null,
        val httpStatus: Int? = null,
        val errorCode: String? = null,
        val failure: Throwable? = null,
    )

    private suspend fun connectOnce(cfg: SocketConfig): Attempt = coroutineScope {
        val done = CompletableDeferred<Attempt>()
        var openedAt = 0L
        fun upFor() = if (openedAt == 0L) 0L else clock() - openedAt

        val request = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + PATH)
            // The token only ever travels in this header, never in the URL (docs/协议.md §2).
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openedAt = clock()
                    current = webSocket
                    webSocket.send(Frames.hello(hello())) // the first frame must be hello
                    onState(ChannelState.Connected)
                    handler.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val frame = Frames.parse(text)) {
                        is ServerFrame.SendSms -> handler.onSendSms(frame.task)
                        ServerFrame.Unknown -> Unit // an unknown frame type is not a reason to drop the connection
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(1000, null) } // echo the close; a code like 1005 may not be sent back
                    done.complete(Attempt(upFor(), closeCode = code, reason = reason))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.complete(Attempt(upFor(), closeCode = code, reason = reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // An upgrade the server refused (e.g. 401 token_revoked) arrives here with the HTTP response.
                    done.complete(Attempt(upFor(), httpStatus = response?.code, errorCode = errorCodeOf(response), failure = t))
                }
            },
        )
        val heartbeat = launch {
            while (isActive) {
                delay(heartbeatMs)
                socket.send(Frames.hello(hello())) // also the keep-alive: the server drops connections that stay silent
            }
        }
        try {
            done.await()
        } finally {
            heartbeat.cancel()
            current = null
            socket.cancel()
        }
    }

    private fun errorCodeOf(response: Response?): String? {
        val text = try {
            response?.body?.string()
        } catch (e: Exception) {
            null
        } ?: return null
        return runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
    }

    private fun reasonFor(verdict: ConnectionVerdict, a: Attempt): String = when {
        verdict == ConnectionVerdict.ADDRESS_SUSPECT -> "服务器地址可能不对（HTTP ${a.httpStatus}）"
        a.closeCode != null -> "服务器关闭了连接（${a.closeCode}）"
        a.failure != null -> "无法连接到服务器"
        else -> "连接已断开"
    }

    @Serializable
    private data class ErrorBody(val error: String? = null)

    private companion object {
        const val PATH = "/api/v1/device/ws"
        const val HEARTBEAT_MS = 60_000L
        const val STABLE_MS = 30_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
