package app.nya.smsforward.node.inbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/** Contact names for the inbox. Optional: without READ_CONTACTS (or for sender ids like "10086") the number is shown. */
object ContactNames {
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = "\u0000"

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun lookup(context: Context, address: String): String? {
        if (address.isBlank() || !canRead(context)) return null
        val hit = cache[address]
        if (hit != null) return hit.takeIf { it != NONE }
        val name = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        cache[address] = name ?: NONE
        return name
    }

    fun clear() = cache.clear()
}
