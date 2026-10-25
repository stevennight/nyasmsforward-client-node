package app.nya.smsforward.node.view

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SseTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val p = SseParser()
        return lines.mapNotNull { p.feed(it) }
    }

    @Test
    fun `an event ends at the blank line and keeps its id`() {
        val events = parse("retry: 3000", "", "id: 1042", "event: message", """data: {"id":1042}""", "")
        assertEquals(listOf(SseEvent("message", """{"id":1042}""", "1042")), events)
    }

    @Test
    fun `comments and data-less events produce nothing, data lines join, the type defaults to message`() {
        val events = parse(":keepalive", "", "data: a", "data:b", "data:  c", "")
        assertEquals(listOf(SseEvent("message", "a\nb\n c", null)), events)
    }

    @Test
    fun `the id is only reported on the event that carried it`() {
        val events = parse("id: 7", "event: message", "data: {}", "", "event: read", """data: {"ids":[7]}""", "")
        assertEquals("7", events[0].id)
        assertNull(events[1].id)
    }

    @Test
    fun `server events are parsed, unknown and broken ones are ignored`() {
        val message = ServerEvent.parse(
            SseEvent(
                "message",
                """{"id":1042,"deviceId":"d1","direction":"in","origin":"device","peer":"106901234","body":"验证码 583921",
                   "code":"583921","deviceTime":1789830000000,"notify":true,"somethingNew":1}""",
                "1042",
            ),
        )
        assertIs<ServerEvent.Message>(message)
        assertEquals(1042, message.message.id)
        assertEquals("583921", message.message.code)
        assertEquals(true, message.notify)
        assertEquals(true, message.message.unread)

        assertEquals(ServerEvent.Read(listOf(1042, 1043)), ServerEvent.parse(SseEvent("read", """{"ids":[1042,1043]}""", null)))
        assertEquals(ServerEvent.Deleted(listOf(5)), ServerEvent.parse(SseEvent("deleted", """{"ids":[5]}""", null)))
        assertEquals(ServerEvent.Device("d1", true, 82), ServerEvent.parse(SseEvent("device", """{"deviceId":"d1","online":true,"battery":82}""", null)))
        assertEquals(ServerEvent.Resync, ServerEvent.parse(SseEvent("resync", "{}", null)))
        assertEquals(ServerEvent.Unknown, ServerEvent.parse(SseEvent("future", "{}", null)))
        assertEquals(ServerEvent.Unknown, ServerEvent.parse(SseEvent("message", "not json", null)))
    }
}
