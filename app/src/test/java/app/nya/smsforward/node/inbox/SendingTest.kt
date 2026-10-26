package app.nya.smsforward.node.inbox

import android.provider.Telephony
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendingTest {
    private val delivered = Telephony.Sms.STATUS_COMPLETE
    private val pending = Telephony.Sms.STATUS_PENDING
    private val failed = Telephony.Sms.STATUS_FAILED

    @Test
    fun `gsm delivery reports follow TP-Status`() {
        assertEquals(delivered, LocalSender.deliveryStatus(0x00, "3gpp"))
        assertEquals(delivered, LocalSender.deliveryStatus(0x02, "3gpp"), "replaced by the service centre still counts as delivered")
        assertEquals(pending, LocalSender.deliveryStatus(0x20, "3gpp"), "congestion: the centre keeps trying")
        assertEquals(failed, LocalSender.deliveryStatus(0x40, "3gpp"))
        assertEquals(failed, LocalSender.deliveryStatus(0x63, null), "no format means GSM")
    }

    @Test
    fun `cdma delivery reports carry the error class in bits 24 and 25`() {
        assertEquals(delivered, LocalSender.deliveryStatus(0x00_00_00_00, "3gpp2"))
        assertEquals(pending, LocalSender.deliveryStatus(0x02 shl 24, "3gpp2"))
        assertEquals(failed, LocalSender.deliveryStatus(0x03 shl 24, "3gpp2"))
    }

    @Test
    fun `a sent message shows its delivery report, a received one never does`() {
        fun row(type: Int, status: Int) = SmsRow(1, 1, "10086", "x", 0, type, true, status = status)
        assertTrue(row(Telephony.Sms.MESSAGE_TYPE_SENT, delivered).delivered)
        assertTrue(row(Telephony.Sms.MESSAGE_TYPE_SENT, failed).notDelivered)
        assertTrue(!row(Telephony.Sms.MESSAGE_TYPE_SENT, pending).delivered && !row(Telephony.Sms.MESSAGE_TYPE_SENT, pending).notDelivered)
        assertTrue(!row(Telephony.Sms.MESSAGE_TYPE_INBOX, delivered).delivered)
        assertTrue(row(Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_NONE).failed)
    }

    @Test
    fun `drafts never become the newest message of a conversation`() {
        val rows = listOf(
            SmsRow(1, 7, "10086", "收到", 100, Telephony.Sms.MESSAGE_TYPE_INBOX, false),
            SmsRow(2, 7, "10086", "还没发", 200, Telephony.Sms.MESSAGE_TYPE_DRAFT, true),
        )
        val c = Conversations.group(rows).single()
        assertEquals(1, c.last.id)
        assertEquals(1, c.unread)
    }
}
