package app.nya.smsforward.node.view

/**
 * Where a platform message came from, for the 「平台」 tab: "本机" for this phone's own receiver identity, otherwise the
 * receiver phone's name; then the SIM card by the admin's label, or its last four digits.
 */
fun sourceLabel(deviceId: String, cardNumber: String?, state: ViewState): String {
    val phone = when {
        deviceId.isNotEmpty() && deviceId == state.ownDeviceId -> "本机"
        else -> state.phones[deviceId]?.name?.takeIf { it.isNotBlank() } ?: "其他手机"
    }
    val card = cardNumber?.takeIf { it.isNotBlank() }?.let { n ->
        state.cards[n]?.label?.takeIf { it.isNotBlank() } ?: "尾号${n.takeLast(4)}"
    }
    return listOfNotNull(phone, card).joinToString(" · ")
}

/** Whether a message (e.g. from the event stream) is part of [thread]: same number, through the same card (or phone). */
fun RemoteMessage.belongsTo(thread: RemoteConversation): Boolean =
    peerKey == thread.peerKey &&
        if (thread.cardNumber.isNullOrEmpty()) cardNumber.isNullOrEmpty() && deviceId == thread.deviceId else cardNumber == thread.cardNumber
