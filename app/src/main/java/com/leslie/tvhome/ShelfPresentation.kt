package com.leslie.tvhome

/** Local presentation choices, separate from feed availability and account data. */
internal object ShelfPresentation {
    val intervals = listOf(8, 15, 30, 60)
    fun seconds(value: Int) = value.takeIf { it in intervals } ?: 8
    fun delayMs(rotate: Boolean, seconds: Int): Long? =
        if (rotate) ShelfPresentation.seconds(seconds) * 1_000L else null
    fun enabled(pkg: String, wallpaperPackages: Set<String>) = pkg !in wallpaperPackages
}
