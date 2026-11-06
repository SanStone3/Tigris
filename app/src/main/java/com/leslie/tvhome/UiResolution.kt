package com.leslie.tvhome

import android.app.Activity
import android.hardware.display.DisplayManager
import android.view.Display

/** A UHD output mode alone is not evidence of a UHD application window. */
internal object UiResolution {
    fun nativeUhd(width: Int, height: Int) = maxOf(width, height) >= 3840 && minOf(width, height) >= 2160

    fun describe(activity: Activity): String {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val width = decor.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: metrics.heightPixels
        val mode = activity.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val output = mode?.let { "${it.physicalWidth}×${it.physicalHeight}" } ?: "?"
        return "UI $width×$height · ${if (I18n.isChinese()) "输出" else "output"} $output"
    }
}
