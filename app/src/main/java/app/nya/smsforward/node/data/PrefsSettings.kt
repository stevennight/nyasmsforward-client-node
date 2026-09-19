package app.nya.smsforward.node.data

import android.content.Context
import app.nya.smsforward.node.node.NodeSettings

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
}
