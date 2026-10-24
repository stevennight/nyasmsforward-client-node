package app.nya.smsforward.node.inbox

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.nya.smsforward.node.BuildConfig

/** What [SmsAppFeature.reconcile] sees: the user's switch, a turn-off waiting on the user, and what Android says now. */
internal data class SmsAppState(val switchOn: Boolean, val turningOff: Boolean, val isDefault: Boolean, val componentsOn: Boolean)

/** What it should be afterwards. */
internal data class SmsAppStep(val switchOn: Boolean, val turningOff: Boolean, val componentsOn: Boolean)

/**
 * The rules, apart from Android so they can be tested.
 *
 * While this app is the default SMS app the feature stays on whatever the switch says: without the components nobody
 * would store incoming SMS any more, and they would be lost. So switching off while default only notes the wish
 * ([SmsAppState.turningOff]); it completes once the user has picked another SMS app in the system settings.
 */
internal fun planSmsApp(s: SmsAppState): SmsAppStep = when {
    s.isDefault -> SmsAppStep(switchOn = true, turningOff = s.turningOff, componentsOn = true)
    s.turningOff -> SmsAppStep(switchOn = false, turningOff = false, componentsOn = false)
    else -> SmsAppStep(switchOn = s.switchOn, turningOff = false, componentsOn = s.switchOn)
}

/**
 * The "短信应用" feature: the components Android requires of a default SMS app (registered disabled in the manifest)
 * are enabled only while the user has it switched on. Until then the app is not even offered as an SMS app, and it
 * never asks for the permissions that come with the role.
 */
object SmsAppFeature {
    private const val PREFS = "node_settings" // the same file as PrefsSettings, so a migration moves it along
    private const val KEY_ON = "sms_app_enabled"
    private const val KEY_TURNING_OFF = "sms_app_turning_off"

    // The alias' name comes from the manifest namespace, which is the same in every flavor (unlike the package name).
    private const val ENTRY_ALIAS = "app.nya.smsforward.node.SmsEntry"

    enum class OffResult {
        DONE,

        /** Still the default SMS app: the user has to pick another one first; the switch-off finishes after that. */
        SWITCH_DEFAULT_FIRST,
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The old full edition had all of this from the start, so its last builds keep it on by default. */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, BuildConfig.LEGACY_FULL)

    fun isTurningOff(context: Context): Boolean = prefs(context).getBoolean(KEY_TURNING_OFF, false)

    /** Switches the feature on: the components first, so the system's "default SMS app" dialog can offer this app. */
    fun turnOn(context: Context) {
        prefs(context).edit().putBoolean(KEY_ON, true).putBoolean(KEY_TURNING_OFF, false).apply()
        setComponents(context, true)
    }

    fun turnOff(context: Context): OffResult {
        if (SmsStore.isDefaultSmsApp(context)) {
            prefs(context).edit().putBoolean(KEY_TURNING_OFF, true).apply()
            return OffResult.SWITCH_DEFAULT_FIRST
        }
        prefs(context).edit().putBoolean(KEY_ON, false).putBoolean(KEY_TURNING_OFF, false).apply()
        setComponents(context, false)
        return OffResult.DONE
    }

    /** Gives up a waiting switch-off (the user changed their mind before picking another SMS app). */
    fun cancelTurnOff(context: Context) {
        prefs(context).edit().putBoolean(KEY_TURNING_OFF, false).apply()
    }

    /**
     * Brings the switch and the components in line with what Android says now. Runs whenever the process starts and on
     * every refresh of the screens, which is how a switch-off completes after the user picked another SMS app.
     */
    fun reconcile(context: Context) {
        val now = SmsAppState(
            switchOn = isOn(context),
            turningOff = isTurningOff(context),
            isDefault = SmsStore.isDefaultSmsApp(context),
            componentsOn = componentsEnabled(context),
        )
        val next = planSmsApp(now)
        if (next.switchOn != now.switchOn || next.turningOff != now.turningOff) {
            prefs(context).edit().putBoolean(KEY_ON, next.switchOn).putBoolean(KEY_TURNING_OFF, next.turningOff).apply()
        }
        if (next.componentsOn != now.componentsOn) setComponents(context, next.componentsOn)
    }

    private fun components(context: Context): List<ComponentName> = listOf(
        ComponentName(context, SmsDeliverReceiver::class.java),
        ComponentName(context, MmsDeliverReceiver::class.java),
        ComponentName(context, RespondViaMessageService::class.java),
        ComponentName(context.packageName, ENTRY_ALIAS),
    )

    /** True only when all of them are on; the manifest default ("enabled" placeholder) differs per flavor. */
    fun componentsEnabled(context: Context): Boolean {
        val pm = context.packageManager
        return components(context).all { c ->
            when (pm.getComponentEnabledSetting(c)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> BuildConfig.LEGACY_FULL
                else -> false
            }
        }
    }

    private fun setComponents(context: Context, on: Boolean) {
        val pm = context.packageManager
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        // DONT_KILL_APP: this runs from the UI and from receivers; restarting the process for it would only hurt.
        for (c in components(context)) pm.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP)
    }
}
