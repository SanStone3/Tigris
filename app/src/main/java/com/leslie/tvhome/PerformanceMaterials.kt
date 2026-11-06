package com.leslie.tvhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** A cheap plate for the smooth profile: no scene copies, blur, refraction or pixel loops. */
internal object PerformanceMaterials {
    fun dock(width: Int, height: Int, radius: Float): Bitmap {
        val image = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val rect = RectF(1f, 1f, image.width - 1f, image.height - 1f)
        val r = radius.coerceAtMost(minOf(image.width, image.height) / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(image).apply {
            paint.color = Color.argb(84, 24, 28, 36)
            drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(70, 255, 255, 255)
            drawRoundRect(rect, r, r, paint)
        }
        return image
    }
}
