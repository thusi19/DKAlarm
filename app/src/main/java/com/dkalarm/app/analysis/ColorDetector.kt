package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.dkalarm.app.model.AttackColor
import kotlin.math.max
import kotlin.math.min

interface ColorDetector {
    fun detect(bitmap: Bitmap, row: Rect, commandArea: Rect? = null): Pair<AttackColor, Float>
}

class HsvColorDetector : ColorDetector {
    override fun detect(bitmap: Bitmap, row: Rect, commandArea: Rect?): Pair<AttackColor, Float> {
        val command = commandArea ?: Rect(0, row.top, (bitmap.width * 0.16f).toInt(), row.bottom)
        val commandWidth = command.width().coerceAtLeast(1)
        // DK attack colour is on the small flag/icon at the far left. Avoid the beige row,
        // command text and the brown edit/pencil icon which previously polluted the result.
        val x0 = max(0, command.left + (commandWidth * 0.06f).toInt())
        val x1 = min(bitmap.width, command.left + (commandWidth * 0.34f).toInt()).coerceAtLeast(x0 + 1)
        val y0 = row.top.coerceAtLeast(0)
        val y1 = row.bottom.coerceAtMost(bitmap.height)

        val counts = mutableMapOf<AttackColor, Int>()
        var saturated = 0
        val hsv = FloatArray(3)
        for (y in y0 until y1) for (x in x0 until x1) {
            Color.colorToHSV(bitmap.getPixel(x, y), hsv)
            if (hsv[1] < 0.36f || hsv[2] < 0.20f) continue
            val c = when {
                hsv[0] < 18f || hsv[0] >= 345f -> AttackColor.RED
                hsv[0] in 18f..52f -> AttackColor.BROWN
                hsv[0] in 53f..165f -> AttackColor.GREEN
                hsv[0] in 166f..260f -> AttackColor.BLUE
                else -> AttackColor.UNKNOWN
            }
            if (c != AttackColor.UNKNOWN) {
                saturated++
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        if (saturated < 5) return AttackColor.UNKNOWN to 0.25f
        val best = counts.maxByOrNull { it.value } ?: return AttackColor.UNKNOWN to 0.25f
        val confidence = (best.value.toFloat() / saturated).coerceIn(0f, 1f)
        return best.key to confidence
    }
}
