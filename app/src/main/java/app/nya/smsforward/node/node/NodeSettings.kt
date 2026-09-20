package app.nya.smsforward.node.node

import app.nya.smsforward.node.policy.SendPolicy

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

    /**
     * What the platform may make this phone send (docs/协议.md §7). Set only here on the phone, reported to the server, and
     * enforced locally whatever the server says. Off by default: receiving works without ever enabling this.
     */
    var sendPolicy: SendPolicy

    /** At most this many send tasks start per rolling hour; the platform cannot raise it. */
    var sendLimitPerHour: Int

    /** Optional local allowlist. Empty means no extra recipient restriction. Values are normalized phone numbers. */
    var allowedRecipients: Set<String>

    /**
     * Also report the SMS the user sends from the phone's own SMS app (docs/协议.md §5.1). Off by default: it needs READ_SMS,
     * which is only requested when this is switched on.
     */
    var syncSent: Boolean

    /** Days of existing history to report once when sync is on (0, 7 or 30). Reported quietly: no alerts, already read. */
    var backfillDays: Int

    /** How much history has been reported already (days), so switching on again does not repeat it. */
    var backfilledDays: Int

    /** Highest sent-box row id already handled; -1 until sync is switched on and the current end of the box is noted. */
    var sentCursor: Long
}

/** Where the long-lived device token is kept (Android Keystore-encrypted in production). */
interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

fun NodeSettings.isPaired(tokens: TokenStore): Boolean = serverUrl != null && deviceId != null && tokens.read() != null

/**
 * Whether the send channel should be running: paired, the token still good, and sending switched on. Receiving and
 * reporting SMS need none of this, so a phone whose policy is "off" never runs the foreground service.
 */
fun NodeSettings.channelWanted(tokens: TokenStore): Boolean = isPaired(tokens) && !needsPairing && sendPolicy != SendPolicy.OFF
