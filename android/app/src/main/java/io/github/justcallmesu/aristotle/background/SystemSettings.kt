package io.github.justcallmesu.aristotle.background

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * The system screens the battery guide opens (T10). The HyperOS ones are not public API: each target is
 * tried in turn and the app's own info page is the last resort, which on HyperOS also holds "Battery saver"
 * and "Autostart".
 */
object SystemSettings {
    fun ignoresBatteryOptimisation(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's own one-tap dialog; a sideloaded personal app may ask for it (spec Q5, Q11). */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimisation(context: Context) = openFirst(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** MIUI/HyperOS per-app battery saver ("No restrictions"). */
    fun openBatterySaver(context: Context) = openFirst(
        context,
        Intent().setComponent(ComponentName(POWER_KEEPER, "$POWER_KEEPER.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString()),
    )

    /** MIUI/HyperOS Autostart list. */
    fun openAutostart(context: Context) = openFirst(
        context,
        Intent().setComponent(ComponentName(SECURITY_CENTER, "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    )

    fun openNotificationSettings(context: Context) = openFirst(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /** One channel's system page (TS1); Android owns the switch. */
    fun openChannelSettings(context: Context, channelId: String) = openFirst(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /** Starts the first intent that resolves and may be started, else the app info page. */
    private fun openFirst(context: Context, vararg candidates: Intent) {
        val all = candidates.toList() + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        for (intent in all) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Not on this phone; try the next.
            } catch (_: SecurityException) {
                // Exists but is not exported to other apps; try the next.
            }
        }
    }

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    private const val POWER_KEEPER = "com.miui.powerkeeper"
    private const val SECURITY_CENTER = "com.miui.securitycenter"
}
