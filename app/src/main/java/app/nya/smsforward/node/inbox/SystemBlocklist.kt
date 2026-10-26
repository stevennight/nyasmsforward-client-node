package app.nya.smsforward.node.inbox

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.util.Log

/**
 * The phone's own block list (BlockedNumberContract), shared with the dialer and every SMS app. Android drops SMS and
 * calls from these numbers before any app sees them, so such messages reach neither the inbox nor the platform. Only the
 * default SMS app (or dialer) may read and change it; everything here answers "nothing" otherwise.
 */
object SystemBlocklist {
    fun available(context: Context): Boolean =
        SmsStore.isDefaultSmsApp(context) && runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    /** The blocked numbers as they were entered (the dialer shows them the same way). */
    fun list(context: Context): List<String> {
        if (!available(context)) return emptyList()
        return try {
            context.contentResolver.query(BlockedNumbers.CONTENT_URI, arrayOf(BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)
                ?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) } }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "could not read the system block list", e)
            emptyList()
        }
    }

    fun isBlocked(context: Context, number: String): Boolean =
        available(context) && runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)

    fun add(context: Context, number: String): Boolean {
        val value = number.trim()
        if (value.isEmpty() || !available(context)) return false
        return try {
            context.contentResolver.insert(BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, value) }) != null
        } catch (e: Exception) {
            Log.w(TAG, "could not add to the system block list", e)
            false
        }
    }

    fun remove(context: Context, number: String): Boolean =
        available(context) && runCatching { BlockedNumberContract.unblock(context, number) > 0 }.getOrDefault(false)

    private const val TAG = "SystemBlocklist"
}
