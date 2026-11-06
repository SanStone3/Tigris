package com.leslie.tvhome

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.leslie.tvhome.tv.TvControls

/** This fork leaves idle timing to the firmware. Never guess another vendor's dream. */
internal object SystemScreensaver {
    fun enforce(ctx: Context) {
        val prefs = Prefs(ctx)
        val mine = ComponentName(ctx, PhotoDream::class.java)
        val desired = if (prefs.systemScreensaver) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        runCatching {
            if (ctx.packageManager.getComponentEnabledSetting(mine) != desired) {
                ctx.packageManager.setComponentEnabledSetting(mine, desired, PackageManager.DONT_KILL_APP)
            }
            if (!prefs.systemScreensaver || !Guard.hasSecureSettings(ctx)) return
            val current = Settings.Secure.getString(ctx.contentResolver, "screensaver_components").orEmpty()
            val previous = prefs.prevScreensaver
            val valid = previous.split(':').filter { value ->
                val component = ComponentName.unflattenFromString(value) ?: return@filter false
                runCatching { ctx.packageManager.getServiceInfo(component, 0).enabled }.getOrDefault(false)
            }.toSet()
            SystemScreensaverPolicy.restore(current, previous, ctx.packageName, valid)?.let { restored ->
                if (Settings.Secure.putString(ctx.contentResolver, "screensaver_components", restored)) {
                    Log.i("TigrisFrame", "restored recorded system screensaver: $restored")
                }
            }
        }.onFailure { Log.w("TigrisFrame", "system screensaver handoff needs Settings", it) }
    }

    fun openSettings(activity: Activity) {
        val candidates = listOf(Intent(Settings.ACTION_DREAM_SETTINGS), TvControls(activity).settingsIntent())
        for (intent in candidates) {
            if (runCatching { activity.startActivity(intent); true }.getOrDefault(false)) return
        }
        android.widget.Toast.makeText(activity, R.string.system_screensaver_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }
}
