package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.dkalarm.app.model.CrownState
import kotlin.math.max
import kotlin.math.min

interface CrownDetector {
    fun detect(bitmap: Bitmap, row: Rect): Pair<CrownState, Float>
}

/**
 * High-recall first-pass crown detector.
 * It deliberately treats crown-like icon structure as MAYBE rather than NO.
 * The ROI and thresholds are isolated here so real DK screenshots can tune this class only.
 */
class HighRecallCrownDetector : CrownDetector {
    override fun detect(bitmap: Bitmap, row: Rect): Pair<CrownState, Float> {
        val left = (bitmap.width * 0.02f).toInt()
        val right = (bitmap.width * 0.30f).toInt()
        val top = max(0, row.top)
        val bottom = min(bitmap.height, row.bottom)
        if (right <= left || bottom <= top) return CrownState.MAYBE to 0.45f

        var gold = 0
        var bright = 0
        var dark = 0
        var samples = 0
        val hsv = FloatArray(3)
        for (y in top until bottom step 2) for (x in left until right step 2) {
            val p = bitmap.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            samples++
            if (hsv[0] in 28f..62f && hsv[1] > 0.30f && hsv[2] > 0.45f) gold++
            if (hsv[2] > 0.78f) bright++
            if (hsv[2] < 0.22f) dark++
        }
        if (samples == 0) return CrownState.MAYBE to 0.45f
        val goldRatio = gold.toFloat() / samples
        val brightRatio = bright.toFloat() / samples
        val contrast = min(bright, dark).toFloat() / samples

        val score = (goldRatio * 8f + brightRatio * 0.45f + contrast * 2.2f).coerceIn(0f, 1f)
        return when {
            score >= 0.72f -> CrownState.YES to score
            score >= 0.30f -> CrownState.MAYBE to score
            else -> CrownState.NO to (1f - score)
        }
    }
}
