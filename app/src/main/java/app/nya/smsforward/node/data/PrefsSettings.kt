package app.nya.smsforward.node.data

import android.content.Context
import app.nya.smsforward.node.node.NodeSettings
import app.nya.smsforward.node.policy.SendPolicy

/** [NodeSettings] on SharedPreferences. Nothing secret lives here: the token is in [KeystoreTokenStore]. */
class PrefsSettings(context: Context) : NodeSettings {
    private val prefs = context.applicationContext.getSharedPreferences("node_settings", Context.MODE_PRIVATE)

    override var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) = prefs.edit().putString("server_url", value).apply()

    override var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    override var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(value) = prefs.edit().putString("device_name", value).apply()

    override var needsPairing: Boolean
        get() = prefs.getBoolean("needs_pairing", false)
        set(value) = prefs.edit().putBoolean("needs_pairing", value).apply()

    override var lastUploadAt: Long
        get() = prefs.getLong("last_upload_at", 0L)
        set(value) = prefs.edit().putLong("last_upload_at", value).apply()

    override var lastError: String?
        get() = prefs.getString("last_error", null)
        set(value) = prefs.edit().putString("last_error", value).apply()

    override var sendPolicy: SendPolicy
        get() = SendPolicy.fromWire(prefs.getString("send_policy", null))
        set(value) = prefs.edit().putString("send_policy", value.wire).apply()

    override var sendLimitPerHour: Int
        get() = prefs.getInt("send_limit_per_hour", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        set(value) = prefs.edit().putInt("send_limit_per_hour", value.coerceIn(1, MAX_LIMIT)).apply()

    override var syncSent: Boolean
        get() = prefs.getBoolean("sync_sent", false)
        set(value) = prefs.edit().putBoolean("sync_sent", value).apply()

    override var backfillDays: Int
        get() = prefs.getInt("backfill_days", 0)
        set(value) = prefs.edit().putInt("backfill_days", value).apply()

    override var backfilledDays: Int
        get() = prefs.getInt("backfilled_days", 0)
        set(value) = prefs.edit().putInt("backfilled_days", value).apply()

    override var sentCursor: Long
        get() = prefs.getLong("sent_cursor", -1L)
        set(value) = prefs.edit().putLong("sent_cursor", value).apply()

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 100
    }
}
