package com.leslie.tvhome

/** Effects budgets are independent of wallpaper resolution and never overwrite appearance choices. */
internal object PerformancePolicy {
    const val SMOOTH = "smooth"
    const val BALANCED = "balanced"
    const val FULL = "full"
    val profiles = listOf(SMOOTH, BALANCED, FULL)

    fun normalized(profile: String) = profile.takeIf { it in profiles } ?: BALANCED

    data class Effects(
        val dockScale: Float, val gridScale: Float, val liftDp: Float, val tiltDegrees: Float,
        val enterMs: Long, val exitMs: Long, val dynamicWallpaper: Boolean,
        val glass: Boolean, val extremeGlass: Boolean, val samplePollMs: Long,
        val minimumCaptureMs: Long,
    )

    fun effects(profile: String): Effects = when (normalized(profile)) {
        SMOOTH -> Effects(1.14f, 1.12f, 6f, 0f, 150, 110, false, false, false, 350, 4_000)
        FULL -> Effects(1.20f, 1.16f, 12f, 5f, 230, 150, true, true, true, 80, 500)
        else -> Effects(1.18f, 1.14f, 9f, 3f, 190, 130, true, true, false, 200, 1_000)
    }

    /** SDK version controls API availability elsewhere; it is not a hardware performance score. */
    fun constrained(lowRam: Boolean, totalBytes: Long, cores: Int): Boolean =
        lowRam || totalBytes < 2_500L * 1024 * 1024 || cores < 4
}
