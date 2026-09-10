package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import com.dkalarm.app.model.CrownState
import kotlin.math.max
import kotlin.math.min

interface CrownDetector {
    fun detect(bitmap: Bitmap, row: Rect, commandArea: Rect? = null): Pair<CrownState, Float>
}

/**
 * High-recall crown detector tuned for the DK incoming-command row.
 * The noble marker is a tiny high-contrast light/dark badge over the attack flag.
 * We intentionally keep a broad MAYBE band because a false negative is worse than
 * asking the user to review one row.
 */
class HighRecallCrownDetector : CrownDetector {
    override fun detect(bitmap: Bitmap, row: Rect, commandArea: Rect?): Pair<CrownState, Float> {
        val command = commandArea ?: Rect(0, row.top, (bitmap.width * 0.16f).toInt(), row.bottom)
        val cw = command.width().coerceAtLeast(1)
        val left = max(0, command.left + (cw * 0.05f).toInt())
        val right = min(bitmap.width, command.left + (cw * 0.36f).toInt())
        val top = max(0, row.top)
        val bottom = min(bitmap.height, row.bottom)
        if (right - left < 7 || bottom - top < 7) return CrownState.MAYBE to 0.45f

        // Look for a small patch containing both very light and very dark pixels.
        // The crown badge produces this pattern; the plain flag is mostly dark/grey.
        var best = 0f
        val patch = 9
        val step = 1
        val maxY = bottom - patch
        val maxX = right - patch
        if (maxY < top || maxX < left) return CrownState.MAYBE to 0.45f

        for (y0 in top..maxY step step) {
            for (x0 in left..maxX step step) {
                var light = 0
                var dark = 0
                var coloredBeige = 0
                for (y in y0 until y0 + patch) for (x in x0 until x0 + patch) {
                    val p = bitmap.getPixel(x, y)
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    val gray = (r + g + b) / 3
                    val spread = maxOf(r, g, b) - minOf(r, g, b)
                    if (gray >= 200 && spread <= 48) light++
                    if (gray <= 92) dark++
                    if (r > g + 12 && g > b + 8) coloredBeige++
                }
                if (light >= 4 && dark >= 4) {
                    // Penalize patches that are mainly beige row background touching text.
                    val contrast = min(light, dark).toFloat() / patch
                    val density = (light + dark).toFloat() / (patch * patch)
                    val beigePenalty = (coloredBeige.toFloat() / (patch * patch)) * 0.35f
                    val score = (contrast * 0.70f + density * 0.55f - beigePenalty).coerceIn(0f, 1f)
                    if (score > best) best = score
                }
            }
        }

        return when {
            best >= 0.78f -> CrownState.YES to best
            best >= 0.40f -> CrownState.MAYBE to best
            else -> CrownState.NO to (1f - best)
        }
    }
}
