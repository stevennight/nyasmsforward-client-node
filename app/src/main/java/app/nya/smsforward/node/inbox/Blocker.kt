package app.nya.smsforward.node.inbox

import android.content.Context
import app.nya.smsforward.node.data.BlockedSms
import app.nya.smsforward.node.data.SmsBlockList
import app.nya.smsforward.node.sms.BlockRule
import app.nya.smsforward.node.sms.SpamFilter

/**
 * The full edition's "骚扰拦截": an incoming SMS that matches a rule goes to [SmsBlockList] instead of the system inbox
 * and raises no notification. Forwarding is not affected: SMS_RECEIVED still reports it to the server.
 */
class Blocker(context: Context, val list: SmsBlockList, private val now: () -> Long = System::currentTimeMillis) {
    private val store = SmsStore(context)

    /** Keeps the message in the block list and returns true when a rule matches; false means deliver it normally. */
    fun intercept(address: String, body: String, date: Long, dateSent: Long, subId: Int?): Boolean {
        val verdict = SpamFilter.check(address, body, list.rules()) ?: return false
        list.add(
            BlockedSms(
                address = address, body = body, date = date, dateSent = dateSent, subId = subId,
                reason = verdict.reason, detail = verdict.detail, blockedAt = now(),
            ),
        )
        return true
    }

    /** "不是骚扰": writes it into the system inbox (as read) and takes it off the list. */
    fun moveToInbox(id: Long): Boolean {
        val sms = list.find(id) ?: return false
        store.insertRestored(sms.address, sms.body, sms.date, sms.dateSent, android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX, true, sms.subId)
            ?: return false
        return list.remove(id)
    }

    fun blockNumber(address: String): Boolean = list.addRule(BlockRule.NUMBER, address.trim(), now())

    fun list(): List<BlockedSms> {
        list.purge(now())
        return list.list()
    }
}
