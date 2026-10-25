package app.nya.smsforward.node.view

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One raw Server-Sent Event before it is interpreted. */
data class SseEvent(val event: String, val data: String, val id: String?)

/**
 * Turns the lines of an SSE response (docs/协议.md §6.1) into events, one line at a time; [feed] returns an event when a
 * blank line completes one. Comment lines (`:keepalive`) and events without data (`retry:`) produce nothing. Line ends
 * are the reader's business (okio's readUtf8Line splits on \n and drops a \r before it).
 */
class SseParser {
    private var event = ""
    private var id = ""
    private var sawId = false
    private val data = ArrayList<String>()

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            val out = if (data.isEmpty()) null else SseEvent(event.ifEmpty { "message" }, data.joinToString("\n"), id.takeIf { sawId })
            event = ""
            data.clear()
            sawId = false
            return out
        }
        if (line.startsWith(':')) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> data += value
            "id" -> if ('\u0000' !in value) {
                id = value
                sawId = true
            }
        }
        return null
    }
}

/** The events of GET /api/v1/events. Unknown types become [Unknown], never an error. */
sealed interface ServerEvent {
    /** A new message (also the ones sent through the platform). [notify]: whether a client should alert. */
    data class Message(val message: RemoteMessage, val notify: Boolean) : ServerEvent
    data class Read(val ids: List<Long>) : ServerEvent
    data class Deleted(val ids: List<Long>) : ServerEvent
    data class Outbound(val taskId: String, val deviceId: String, val status: String, val error: String?, val messageId: Long?) : ServerEvent
    data class Device(val deviceId: String, val online: Boolean, val battery: Int?) : ServerEvent

    /** Too much changed (history backfill, or more than 500 missed): fetch the lists again. */
    data object Resync : ServerEvent
    data object Unknown : ServerEvent

    companion object {
        fun parse(raw: SseEvent): ServerEvent {
            val data = try {
                OkHttpViewApi.json.parseToJsonElement(raw.data).jsonObject
            } catch (e: Exception) {
                return Unknown // not ours: ignore, never fatal
            }
            return try {
                when (raw.event) {
                    "message" -> Message(
                        OkHttpViewApi.json.decodeFromJsonElement(RemoteMessage.serializer(), data),
                        notify = data.bool("notify") == true,
                    )
                    "read" -> Read(data.ids())
                    "deleted" -> Deleted(data.ids())
                    "outbound" -> Outbound(
                        data.str("taskId").orEmpty(), data.str("deviceId").orEmpty(), data.str("status").orEmpty(),
                        data.str("error")?.ifEmpty { null }, data["messageId"]?.jsonPrimitive?.longOrNull,
                    )
                    "device" -> Device(data.str("deviceId").orEmpty(), data.bool("online") == true, data["battery"]?.jsonPrimitive?.intOrNull)
                    "resync" -> Resync
                    else -> Unknown
                }
            } catch (e: Exception) {
                Unknown
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.ids(): List<Long> = this["ids"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
    }
}
