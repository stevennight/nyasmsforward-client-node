package app.nya.smsforward.node.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsTrashTest {
    private val day = 86_400_000L

    private fun sms(body: String, deletedAt: Long, subId: Int? = 3) = TrashedSms(
        address = "106901234", body = body, date = 1_000, dateSent = 900, type = 1, read = false,
        subId = subId, origin = "platform", deletedAt = deletedAt,
    )

    @Test
    fun `keeps every field and lists the newest deletion first`() {
        val trash = SmsTrash(JdbcSqlDb())
        val first = trash.add(sms("旧", deletedAt = 10))
        trash.add(sms("新", deletedAt = 20, subId = null))

        val all = trash.list()
        assertEquals(listOf("新", "旧"), all.map { it.body })
        assertNull(all[0].subId)
        val back = trash.find(first)!!
        assertEquals(sms("旧", deletedAt = 10).copy(id = first), back)
    }

    @Test
    fun `purges only what is older than thirty days`() {
        val trash = SmsTrash(JdbcSqlDb())
        val now = 100 * day
        trash.add(sms("31 天前", deletedAt = now - 31 * day))
        val keep = trash.add(sms("29 天前", deletedAt = now - 29 * day))

        assertEquals(1, trash.purge(now))
        assertEquals(listOf(keep), trash.list().map { it.id })
        assertEquals(1, SmsTrash.daysLeft(trash.find(keep)!!, now))
    }

    @Test
    fun `restoring removes the entry once`() {
        val trash = SmsTrash(JdbcSqlDb())
        val id = trash.add(sms("x", deletedAt = 1))
        assertTrue(trash.remove(id))
        assertFalse(trash.remove(id))
        assertEquals(0, trash.list().size)
    }
}
