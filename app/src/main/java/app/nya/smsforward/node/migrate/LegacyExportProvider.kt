package app.nya.smsforward.node.migrate

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import app.nya.smsforward.node.BuildConfig
import app.nya.smsforward.node.data.Schema
import app.nya.smsforward.node.node.NodeRuntime
import app.nya.smsforward.node.service.NodeService
import app.nya.smsforward.node.work.SentSyncScheduler
import java.io.File

/**
 * Registered only in the legacyFull flavor (the old full edition's last builds): hands this app's data and pairing to
 * the standard app, which reads it in [LegacyMigration]. Exported, so every entry point first checks that the caller is
 * the standard app and is signed with the same key; anyone else gets a SecurityException.
 *
 * - call("ping"): format, schema version, app version, and whether the handover already happened.
 * - openFile(".../snapshot", "r"): the [MigrationSnapshot] JSON, token included.
 * - call("finish"): the standard app has taken over; this app forgets its pairing and stops reporting.
 */
class LegacyExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = checkCaller()
        return when (method) {
            Migration.METHOD_PING -> Bundle().apply {
                putInt(Migration.KEY_FORMAT, MigrationSnapshot.FORMAT)
                putInt(Migration.KEY_SCHEMA, Schema.LATEST)
                putString(Migration.KEY_VERSION, BuildConfig.VERSION_NAME)
                putBoolean(Migration.KEY_HANDED_OVER, Migration.handedOver(context))
            }
            Migration.METHOD_FINISH -> {
                handOver(context)
                Bundle().apply { putBoolean(Migration.KEY_HANDED_OVER, true) }
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = checkCaller()
        require(mode == "r" && uri.lastPathSegment == Migration.PATH_SNAPSHOT) { "only the snapshot can be read" }
        val runtime = NodeRuntime.get(context)
        val text = MigrationSnapshot.export(
            runtime.database,
            Migration.prefs(context).all,
            runtime.tokens.read(),
            BuildConfig.VERSION_NAME,
        )
        // Written to a private file and unlinked right after opening: the descriptor stays readable, the token does
        // not stay on disk.
        val file = File(context.cacheDir, "migration-snapshot.json")
        file.writeText(text)
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            file.delete()
        }
    }

    @SuppressLint("ApplySharedPref") // on disk before answering: the standard app relies on it right after
    private fun handOver(context: Context) {
        val runtime = NodeRuntime.get(context)
        // Local only: the server must keep the token, which the standard app now uses.
        runtime.pairing.disconnect()
        runtime.settings.syncSent = false
        SentSyncScheduler.disable(context)
        Migration.prefs(context).edit().putBoolean(Migration.KEY_HANDED_OVER, true).commit()
        NodeService.sync(context) // no token any more: the send channel stops
    }

    private fun checkCaller(): Context {
        val context = context ?: throw SecurityException("provider not ready")
        val caller = callingPackage ?: throw SecurityException("unknown caller")
        if (caller != Migration.STANDARD_PACKAGE) throw SecurityException("not the standard app: $caller")
        if (context.packageManager.checkSignatures(context.packageName, caller) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("the standard app is signed with another key")
        }
        return context
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Names shared by both sides of the migration. */
object Migration {
    const val STANDARD_PACKAGE = "app.nya.smsforward.node"
    const val LEGACY_PACKAGE = "app.nya.smsforward.node.full"
    const val AUTHORITY = "$LEGACY_PACKAGE.migration"
    const val PATH_SNAPSHOT = "snapshot"

    const val METHOD_PING = "ping"
    const val METHOD_FINISH = "finish"
    const val KEY_FORMAT = "format"
    const val KEY_SCHEMA = "schema"
    const val KEY_VERSION = "version"
    const val KEY_HANDED_OVER = "migration_handed_over"

    /** Standard app: set once the data came across (or the user chose to start fresh); the offer is not shown again. */
    const val KEY_MIGRATED = "migration_from_full_done"

    fun prefs(context: Context) = context.applicationContext.getSharedPreferences("node_settings", Context.MODE_PRIVATE)

    /** Legacy app: whether it already handed over to the standard app. */
    fun handedOver(context: Context): Boolean = prefs(context).getBoolean(KEY_HANDED_OVER, false)
}
