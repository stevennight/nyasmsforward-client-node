package app.nya.smsforward.node.service

import android.content.Context
import android.os.BatteryManager
import app.nya.smsforward.node.net.ChannelState
import app.nya.smsforward.node.net.Frames
import app.nya.smsforward.node.net.HelloFrame
import app.nya.smsforward.node.net.NodeSocket
import app.nya.smsforward.node.net.OkHttpNodeApi
import app.nya.smsforward.node.net.SocketConfig
import app.nya.smsforward.node.net.SocketEnd
import app.nya.smsforward.node.net.SocketHandler
import app.nya.smsforward.node.node.NodeSettings
import app.nya.smsforward.node.node.Notifier
import app.nya.smsforward.node.node.TokenStore
import app.nya.smsforward.node.node.channelWanted
import app.nya.smsforward.node.send.SendCoordinator
import app.nya.smsforward.node.send.SendReceipt
import app.nya.smsforward.node.send.SendTask
import app.nya.smsforward.node.send.SmsSender
import app.nya.smsforward.node.sms.AndroidSmsBox
import app.nya.smsforward.node.sms.DeleteSms
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The running device channel: one [NodeSocket] wired to sending and deletion, alive while the foreground service is.
 *
 * All the decisions live in [NodeSocket] (connection) and [SendCoordinator] (what to do with a task); this only connects
 * them and turns the socket's final answers into app state.
 */
class SendChannel(
    private val app: Context,
    private val scope: CoroutineScope,
    private val settings: NodeSettings,
    private val tokens: TokenStore,
    private val sender: SmsSender,
    private val smsBox: () -> AndroidSmsBox,
    private val coordinator: () -> SendCoordinator,
    private val state: MutableStateFlow<ChannelState>,
    private val appVersion: String,
) {
    private var job: Job? = null

    @Volatile
    private var socket: NodeSocket? = null

    // A WebSocket must not time out just because nothing arrives for a while (the server pings, we send heartbeats).
    private val client: OkHttpClient by lazy {
        OkHttpNodeApi.defaultClient().newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(45, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /** Sends a receipt if connected; otherwise it stays in the ledger and is replayed after the next reconnect. */
    fun emit(receipt: SendReceipt) {
        socket?.send(Frames.result(receipt))
    }

    /** Re-reports the phone's state now (the policy just changed). */
    fun sendHello() {
        socket?.sendHello()
    }

    /** Starts the channel (idempotent). [onStopped] runs when it ends by itself, i.e. the token was rejected. */
    @Synchronized
    fun start(onStopped: () -> Unit) {
        if (job?.isActive == true) return
        val s = NodeSocket(
            client = client,
            config = {
                val url = settings.serverUrl
                val token = tokens.read()
                if (settings.channelWanted(tokens) && url != null && token != null) SocketConfig(url, token) else null
            },
            hello = ::hello,
            handler = object : SocketHandler {
                override fun onOpen() {
                    // Anything settled while we were disconnected, and anything left mid-send by a process that died.
                    scope.launch(Dispatchers.IO) {
                        val c = coordinator()
                        (c.settleStale() + c.receiptsToReplay()).forEach(::emit)
                    }
                }

                override fun onSendSms(task: SendTask) {
                    scope.launch(Dispatchers.IO) { coordinator().onTask(task) }
                }

                override fun onDeleteSms(request: DeleteSms) {
                    scope.launch(Dispatchers.IO) { socket?.send(Frames.deleteResult(request, smsBox().delete(request))) }
                }
            },
            onState = { state.value = it },
        )
        socket = s
        job = scope.launch(Dispatchers.IO) {
            val end = s.run()
            if (end == SocketEnd.NEEDS_PAIRING) {
                settings.needsPairing = true
                settings.lastError = "令牌已失效，需要重新配对"
                Notifier.needsPairing(app, 0)
            }
            socket = null
            onStopped()
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        socket = null
        if (state.value != ChannelState.NeedsPairing) state.value = ChannelState.Idle
    }

    private fun hello() = HelloFrame(
        appVersion = appVersion,
        battery = battery(),
        sendPolicy = settings.sendPolicy.wire,
        syncSent = settings.syncSent,
        backfillDays = settings.backfillDays,
        sims = sender.activeSims(),
    )

    private fun battery(): Int? {
        val level = app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level?.takeIf { it in 0..100 }
    }
}
