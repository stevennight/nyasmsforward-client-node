package app.nya.smsforward.node.inbox

import android.content.Context
import app.nya.smsforward.node.data.BlockedSms
import app.nya.smsforward.node.data.SmsBlockList
import app.nya.smsforward.node.sms.BlockRule
import app.nya.smsforward.node.sms.PeerKey
import app.nya.smsforward.node.sms.SpamFilter

/**
 * The full edition's "骚扰拦截": an incoming SMS that matches a rule goes to [SmsBlockList] instead of the system inbox
 * and raises no notification. Forwarding is not affected: SMS_RECEIVED still reports it to the server.
 */
class Blocker(private val context: Context, val list: SmsBlockList, private val now: () -> Long = System::currentTimeMillis) {
    private val store = SmsStore(context)

    /** Keeps the message in the block list and returns true when a rule matches; false means deliver it normally. */
    fun intercept(address: String, body: String, date: Long, dateSent: Long, subId: Int?): Boolean {
        val isContact = ContactNames.lookup(context, address) != null
        val verdict = SpamFilter.check(address, body, list.rules(), isContact) ?: return false
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

    /**
     * "信任这个号码": its future messages are never intercepted, and what was already held back from it goes to the inbox.
     * Also drops an exact blacklist entry for it. Returns how many messages were moved.
     */
    fun trustNumber(address: String): Int {
        val number = address.trim()
        dropExact(BlockRule.NUMBER, number)
        list.addRule(BlockRule.ALLOW, number, now())
        return list.list().filter { SpamFilter.numberMatches(number, PeerKey.normalize(it.address)) }.count { moveToInbox(it.id) }
    }

    /** Blacklists a number; a trust entry for exactly that number would win, so it goes. */
    fun blockNumber(address: String): Boolean {
        val number = address.trim()
        dropExact(BlockRule.ALLOW, number)
        return list.addRule(BlockRule.NUMBER, number, now())
    }

    private fun dropExact(kind: String, number: String) {
        val peer = PeerKey.normalize(number)
        list.rules().filter { it.kind == kind && !it.value.endsWith("*") && SpamFilter.numberMatches(it.value, peer) }.forEach { list.removeRule(it.id) }
    }

    fun list(): List<BlockedSms> {
        list.purge(now())
        return list.list()
    }
}
