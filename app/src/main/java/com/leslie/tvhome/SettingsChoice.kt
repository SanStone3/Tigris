package com.leslie.tvhome

/** A dialog's pending value is separate from saved settings until the user confirms. */
internal class SettingsChoice<T>(private val options: List<T>, current: T) {
    init { require(options.isNotEmpty()) }
    val initialIndex = options.indexOf(current).coerceAtLeast(0)
    private var index = initialIndex
    val value: T get() = options[index]
    fun select(candidate: Int) { if (candidate in options.indices) index = candidate }

    companion object {
        // Explicit allowlist: no app order, folders, uploads, credentials, screensaver or chosen image.
        val appearanceKeys = setOf("performance_profile", "dock_columns", "clock_style", "focus_style", "cc_style",
            "extreme_glass", "glass_light_tint", "glass_dark_tint", "reduce_glass_transparency",
            "shelf_rotation", "shelf_seconds", "shelf_wallpaper_packages")
    }
}

/** Local illustration of effect budgets, without applying an option to the running launcher. */
internal object SettingPreviewPolicy {
    data class Sample(val scale: Float, val liftDp: Float, val tiltDegrees: Float, val durationMs: Long, val glass: Boolean, val extremeGlass: Boolean)
    fun sample(profile: String, decorativeFocus: Boolean): Sample = PerformancePolicy.effects(profile).let {
        Sample(it.dockScale, it.liftDp, if (decorativeFocus) it.tiltDegrees else 0f, it.enterMs, it.glass, it.extremeGlass)
    }
}
