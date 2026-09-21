package app.nya.smsforward.node.send

import app.nya.smsforward.node.net.SimInfo
import app.nya.smsforward.node.sms.PeerKey

sealed interface SimChoice {
    /** The task did not name a SIM: use the phone's default SMS SIM. */
    data object Default : SimChoice

    data class Subscription(val id: Int) : SimChoice

    /** The slot has no active SIM right now. Never falls back to the other card: the recipient would see a stranger's number. */
    data object Unavailable : SimChoice

    /** A slot was named but SIM information cannot be read (READ_PHONE_STATE missing), so the slot cannot be mapped. */
    data object NoPermission : SimChoice
}

object SimResolver {
    /** Maps a card number to the subscription that currently owns it. Slot is a legacy fallback only. */
    fun resolve(cardNumber: String?, slot: Int?, sims: List<SimInfo>, canReadSims: Boolean): SimChoice {
        // An unqualified task deliberately uses Android's default SMS SIM. That path does not
        // need READ_PHONE_STATE/READ_PHONE_NUMBERS because it never inspects the SIM list.
        if (cardNumber.isNullOrBlank() && slot == null) return SimChoice.Default
        if (!canReadSims) return SimChoice.NoPermission
        if (!cardNumber.isNullOrBlank()) {
            val key = PeerKey.normalize(cardNumber)
            val id = sims.firstOrNull { sim -> sim.number?.let(PeerKey::normalize) == key }?.subscriptionId
                ?: return SimChoice.Unavailable
            return SimChoice.Subscription(id)
        }
        val id = sims.firstOrNull { it.slot == slot }?.subscriptionId ?: return SimChoice.Unavailable
        return SimChoice.Subscription(id)
    }

    /** Compatibility for existing callers and pre-card tasks. */
    fun resolve(slot: Int?, sims: List<SimInfo>, canReadSims: Boolean): SimChoice = resolve(null, slot, sims, canReadSims)
}
