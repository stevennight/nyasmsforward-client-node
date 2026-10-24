package app.nya.smsforward.node.migrate

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.data.Schema
import app.nya.smsforward.node.inbox.SmsAppFeature
import app.nya.smsforward.node.inbox.SmsStore
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.node.isPaired
import app.nya.smsforward.node.service.NodeService
import app.nya.smsforward.node.work.SentSyncScheduler
import app.nya.smsforward.node.work.UploadScheduler

/** What the standard app found of the old full edition. */
sealed interface LegacyStatus {
    /** Not installed, signed by someone else, already moved, or this is the legacy app itself. */
    data object None : LegacyStatus

    /** Installed, but a version before 0.6.0, which cannot hand anything over yet. */
    data class NeedsUpdate(val version: String) : LegacyStatus

    /**
     * Ready to hand over. [pairedHere]: this app is paired already and keeps its own pairing (only the recycle bin and
     * interception come across). [legacyIsDefault]: the old app is the default SMS app right now.
     */
    data class Ready(val version: String, val pairedHere: Boolean, val legacyIsDefault: Boolean) : LegacyStatus

    /** It handed over already (e.g. to an earlier install of this app); only uninstalling is left. */
    data object HandedOver : LegacyStatus
}

data class MigrationResult(
    /** The legacy app's token and identity came along: same device on the server, no new pairing. */
    val movedPairing: Boolean,
    /** This app was already paired, so it kept its own pairing; the legacy device on the server is left over. */
    val keptOwnPairing: Boolean,
    val trash: Int,
    val blocked: Int,
    val rules: Int,
    val queued: Int,
)

/**
 * The standard app's side of the move off the old full edition ([LegacyExportProvider] is the other side).
 *
 * If this app is not paired yet it takes over everything, the pairing included, so the server keeps seeing the same
 * device. If it is paired already (someone who had both editions installed) it keeps its own pairing and takes only
 * what the full edition had on its own: the recycle bin and interception. Either way the old app is told to stop.
 */
object LegacyMigration {
    private val authorityUri: Uri = Uri.parse("content://${Migration.AUTHORITY}")

    fun detect(context: Context): LegacyStatus {
        if (BuildConfig.LEGACY_FULL) return LegacyStatus.None
        if (Migration.prefs(context).getBoolean(Migration.KEY_MIGRATED, false)) return LegacyStatus.None
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(Migration.LEGACY_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return LegacyStatus.None
        }
        if (pm.checkSignatures(context.packageName, Migration.LEGACY_PACKAGE) != PackageManager.SIGNATURE_MATCH) return LegacyStatus.None
        val version = info.versionName.orEmpty()
        val ping = try {
            context.contentResolver.call(authorityUri, Migration.METHOD_PING, null, null)
        } catch (_: Exception) {
            null // no provider: a version from before the merge
        } ?: return LegacyStatus.NeedsUpdate(version)
        if (ping.getBoolean(Migration.KEY_HANDED_OVER, false)) return LegacyStatus.HandedOver
        val legacyIsDefault = android.provider.Telephony.Sms.getDefaultSmsPackage(context) == Migration.LEGACY_PACKAGE
        val runtime = NodeRuntime.get(context)
        return LegacyStatus.Ready(
            ping.getString(Migration.KEY_VERSION).orEmpty().ifEmpty { version },
            pairedHere = runtime.settings.isPaired(runtime.tokens),
            legacyIsDefault = legacyIsDefault,
        )
    }

    /** Runs the move. Off the main thread. Throws with a message for the user when it cannot. */
    @SuppressLint("ApplySharedPref") // written before the old app is told to stop, so a crash in between loses nothing
    fun run(context: Context): MigrationResult {
        val runtime = NodeRuntime.get(context)
        val text = context.contentResolver.openInputStream(authorityUri.buildUpon().appendPath(Migration.PATH_SNAPSHOT).build())
            ?.use { it.readBytes().decodeToString() }
            ?: error("读不到旧版的数据")
        val snapshot = MigrationSnapshot.parse(text)
        require(snapshot.schema == Schema.LATEST) {
            "旧版（v${snapshot.version}）和本 App（v${BuildConfig.VERSION_NAME}）的数据格式不同，请把两个都更新到最新版再试。"
        }

        val alreadyPaired = runtime.settings.isPaired(runtime.tokens)
        val movingPairing = !alreadyPaired && snapshot.token != null
        val counts = MigrationSnapshot.importTables(runtime.database, snapshot, movingPairing)
        if (movingPairing) {
            val editor = Migration.prefs(context).edit()
            for ((key, value) in snapshot.prefs) {
                if (key in NOT_COPIED) continue
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
            runtime.tokens.write(snapshot.token!!)
        }
        // The full edition always had the inbox; keep it.
        SmsAppFeature.turnOn(context)
        Migration.prefs(context).edit().putBoolean(Migration.KEY_MIGRATED, true).commit()

        // The old app stops reporting (and forgets the token it just handed over). If this fails it keeps reporting
        // under the same identity, which the server de-duplicates, so the move still stands.
        runCatching { context.contentResolver.call(authorityUri, Migration.METHOD_FINISH, null, null) }

        if (movingPairing) {
            UploadScheduler.enqueue(context)
            if (runtime.settings.syncSent) SentSyncScheduler.enable(context)
            NodeService.sync(context)
        }
        return MigrationResult(
            movedPairing = movingPairing,
            keptOwnPairing = alreadyPaired,
            trash = counts["sms_trash"] ?: 0,
            blocked = counts["sms_blocked"] ?: 0,
            rules = counts["block_rules"] ?: 0,
            queued = counts["outbox"] ?: 0,
        )
    }

    /** The user chose to start fresh; do not offer again. */
    fun dismiss(context: Context) {
        Migration.prefs(context).edit().putBoolean(Migration.KEY_MIGRATED, true).apply()
    }

    fun isDefaultHere(context: Context) = SmsStore.isDefaultSmsApp(context)

    /** The legacy app's system page, where the user uninstalls it (no permission needed, unlike ACTION_DELETE). */
    fun openLegacyAppInfo(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${Migration.LEGACY_PACKAGE}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Keys of node_settings that describe the legacy app itself, not the user's settings. */
    private val NOT_COPIED = setOf(Migration.KEY_HANDED_OVER, Migration.KEY_MIGRATED, "sms_app_turning_off")
}
