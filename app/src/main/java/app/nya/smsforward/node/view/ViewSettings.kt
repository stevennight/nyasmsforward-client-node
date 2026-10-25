package app.nya.smsforward.node.view

import android.content.Context

/**
 * The 「查看」 feature's own state, apart from the receiver's (node/NodeSettings): its server address, client identity
 * and permissions. The client token itself is in a separate KeystoreTokenStore. Nothing secret lives here.
 */
interface ViewSettings {
    /** The 「查看」 switch: shows the 「平台」 tab. Off by default. */
    var enabled: Boolean
    var serverUrl: String?
    var deviceId: String?
    var deviceName: String?
    var scopes: Set<String>

    /** The server said the client token is dead (revoked in the console): log in again. */
    var needsLogin: Boolean
}

class PrefsViewSettings(context: Context) : ViewSettings {
    private val prefs = context.applicationContext.getSharedPreferences("view_settings", Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    override var serverUrl: String?
        get() = prefs.getString("server_url", null)
        set(value) = prefs.edit().putString("server_url", value).apply()

    override var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    override var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(value) = prefs.edit().putString("device_name", value).apply()

    override var scopes: Set<String>
        get() = prefs.getStringSet("scopes", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("scopes", value.toSet()).apply()

    override var needsLogin: Boolean
        get() = prefs.getBoolean("needs_login", false)
        set(value) = prefs.edit().putBoolean("needs_login", value).apply()
}
