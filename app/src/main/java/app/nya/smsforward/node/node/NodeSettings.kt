package app.nya.smsforward.node.node

/** Non-secret state of this phone's connection to the server. The token itself lives in [TokenStore]. */
interface NodeSettings {
    /** Normalized server address, e.g. `https://sms.example.com`. Survives sign-out, so re-pairing is one field shorter. */
    var serverUrl: String?

    /** Set while paired (or waiting to be re-paired); the server-side identity that dedupe keys are built from. */
    var deviceId: String?
    var deviceName: String?

    /** True once the server said the token is dead. SMS keep arriving and queueing; uploads pause until re-pairing. */
    var needsPairing: Boolean

    var lastUploadAt: Long
    var lastError: String?
}

/** Where the long-lived device token is kept (Android Keystore-encrypted in production). */
interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

fun NodeSettings.isPaired(tokens: TokenStore): Boolean = serverUrl != null && deviceId != null && tokens.read() != null
