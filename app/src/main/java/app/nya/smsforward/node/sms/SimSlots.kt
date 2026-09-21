package app.nya.smsforward.node.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import app.nya.smsforward.node.net.SimInfo

/** Works out which SIM slot (1-based) an SMS arrived on, and lists the SIMs for the pairing request. */
object SimSlots {
    /**
     * The SMS_RECEIVED broadcast names the slot in `slot` (0-based) on most devices, and the subscription id in
     * `subscription` (or `android.telephony.extra.SUBSCRIPTION_INDEX`). Returns null when neither resolves.
     */
    fun slotOf(intent: Intent): Int? {
        val slot = intent.getIntExtra("slot", -1)
        if (slot >= 0) return slot + 1
        val subId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        if (subId < 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val index = SubscriptionManager.getSlotIndex(subId)
            if (index >= 0) return index + 1
        }
        return null
    }

    /** Active SIMs. Needs READ_PHONE_STATE; without it the list is empty and only slot numbers are reported later. */
    @SuppressLint("MissingPermission") // guarded by the explicit check below
    fun activeSims(context: Context): List<SimInfo> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val canReadNumbers = context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            manager.activeSubscriptionInfoList.orEmpty().map {
                SimInfo(
                    slot = it.simSlotIndex + 1,
                    subscriptionId = it.subscriptionId,
                    label = (it.carrierName ?: it.displayName)?.toString()?.takeIf(String::isNotBlank),
                    // SubscriptionInfo.number is empty on some Android/OEM builds even when the permission is
                    // granted. Android 13 added a subscription-aware lookup that checks the carrier/UICC/IMS
                    // sources; use it first and retain the old value as a compatibility fallback.
                    number = phoneNumber(manager, it, canReadNumbers),
                )
            }.sortedBy { it.slot }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun phoneNumber(manager: SubscriptionManager, info: android.telephony.SubscriptionInfo, canReadNumbers: Boolean): String? {
        if (!canReadNumbers) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val number = runCatching { manager.getPhoneNumber(info.subscriptionId) }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
            if (number != null) return number
        }
        return runCatching { info.number?.takeIf(String::isNotBlank) }.getOrNull()
    }
}
