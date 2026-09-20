package app.nya.smsforward.node.send

import app.nya.smsforward.node.net.SimInfo

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
    /**
     * Maps the 1-based [slot] of a task to the subscription that has to send it.
     *
     * The slot number is the stable thing (it is what the server stored when the original message arrived); the
     * subscription id is looked up now, because it changes when a SIM is swapped.
     */
    fun resolve(slot: Int?, sims: List<SimInfo>, canReadSims: Boolean): SimChoice {
        if (slot == null) return SimChoice.Default
        if (!canReadSims) return SimChoice.NoPermission
        val id = sims.firstOrNull { it.slot == slot }?.subscriptionId ?: return SimChoice.Unavailable
        return SimChoice.Subscription(id)
    }
}
