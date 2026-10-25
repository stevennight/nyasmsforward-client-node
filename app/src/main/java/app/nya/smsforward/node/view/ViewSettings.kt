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

    /** Keep the stream (and the foreground service) running behind the app, to alert about new messages. On by default. */
    var backgroundAlerts: Boolean

    /** The last event id seen, so a restarted process asks the server to replay what it missed. */
    var lastEventId: String?

    /** List this phone's own conversations under 「全部」 even though the 「短信」 tab has them. Off by default. */
    var showOwn: Boolean

    /** Deleting in the 「短信」 inbox also moves the platform's copy to the platform recycle bin. Off by default. */
    var deleteOnPlatform: Boolean
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

    override var backgroundAlerts: Boolean
        get() = prefs.getBoolean("background_alerts", true)
        set(value) = prefs.edit().putBoolean("background_alerts", value).apply()

    override var lastEventId: String?
        get() = prefs.getString("last_event_id", null)
        set(value) = prefs.edit().putString("last_event_id", value).apply()

    override var showOwn: Boolean
        get() = prefs.getBoolean("show_own", false)
        set(value) = prefs.edit().putBoolean("show_own", value).apply()

    override var deleteOnPlatform: Boolean
        get() = prefs.getBoolean("delete_on_platform", false)
        set(value) = prefs.edit().putBoolean("delete_on_platform", value).apply()
}
