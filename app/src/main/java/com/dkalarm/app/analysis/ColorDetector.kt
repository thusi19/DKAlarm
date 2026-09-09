package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.dkalarm.app.model.AttackColor
import kotlin.math.max

interface ColorDetector {
    fun detect(bitmap: Bitmap, row: Rect): Pair<AttackColor, Float>
}

class HsvColorDetector : ColorDetector {
    override fun detect(bitmap: Bitmap, row: Rect): Pair<AttackColor, Float> {
        val x0 = max(0, (bitmap.width * 0.02).toInt())
        val x1 = max(x0 + 1, (bitmap.width * 0.42).toInt())
        val y0 = row.top.coerceAtLeast(0)
        val y1 = row.bottom.coerceAtMost(bitmap.height)
        val counts = mutableMapOf<AttackColor, Int>()
        var saturated = 0
        val hsv = FloatArray(3)
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            Color.colorToHSV(bitmap.getPixel(x, y), hsv)
            if (hsv[1] < 0.20f || hsv[2] < 0.18f) continue
            saturated++
            val c = when {
                hsv[0] < 18f || hsv[0] >= 345f -> AttackColor.RED
                hsv[0] in 18f..52f && hsv[1] > 0.30f -> AttackColor.BROWN
                hsv[0] in 53f..165f -> AttackColor.GREEN
                hsv[0] in 166f..260f -> AttackColor.BLUE
                else -> AttackColor.UNKNOWN
            }
            counts[c] = (counts[c] ?: 0) + 1
        }
        if (saturated < 8) return AttackColor.UNKNOWN to 0.2f
        val best = counts.maxByOrNull { it.value } ?: return AttackColor.UNKNOWN to 0.2f
        return best.key to (best.value.toFloat() / saturated).coerceIn(0f, 1f)
    }
}
