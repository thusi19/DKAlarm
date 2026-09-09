package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import com.dkalarm.app.model.AttackCandidate
import com.dkalarm.app.model.OcrField
import com.dkalarm.app.model.ValidationState
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.max

class ScreenshotAnalyzer(
    private val ocrEngine: OcrEngine = MlKitOcrEngine(),
    private val rowDetector: RowDetector = HeuristicRowDetector(),
    private val colorDetector: ColorDetector = HsvColorDetector(),
    private val crownDetector: CrownDetector = HighRecallCrownDetector(),
    private val classifier: AttackClassifier = AttackClassifier(),
    private val timeValidator: TimeValidator = TimeValidator()
) {
    private val timeRegex = Regex("(?<!\\d)(?:[0-2]?\\d):[0-5]\\d:[0-5]\\d(?!\\d)")

    suspend fun analyze(bitmap: Bitmap, capturedAt: Instant = Instant.now()): List<AttackCandidate> {
        val hash = hashBitmap(bitmap)
        val ocr = ocrEngine.recognize(bitmap)
        val rows = rowDetector.detect(ocr.tokens, bitmap.width, bitmap.height)

        return rows.mapIndexedNotNull { index, row ->
            val rowTokens = ocr.tokens.filter { token -> token.bounds.centerY() in row.top..row.bottom }
            val text = rowTokens.sortedWith(compareBy<OcrToken> { it.bounds.centerY() }.thenBy { it.bounds.left })
                .joinToString(" ") { it.text }
            val times = timeRegex.findAll(text).map { it.value }.toList()
            if (times.isEmpty()) return@mapIndexedNotNull null

            val absoluteText = detectAbsoluteText(text, times)
            val relativeText = detectRelativeText(text, times)
            val parsed = timeValidator.parse(absoluteText, relativeText, capturedAt)
            val target = detectTargetVillage(rowTokens, bitmap.width)
            val (color, colorConfidence) = colorDetector.detect(bitmap, row)
            val (crown, crownScore) = crownDetector.detect(bitmap, row)
            val kind = classifier.classify(crown, color)

            val warnings = buildList {
                addAll(parsed.warnings)
                if (target.confidence < 0.65f) add("Cílovou vesnici zkontroluj ručně.")
                if (crown.name == "MAYBE") add("Korunka je nejistá – rozhodni ANO/NE před vytvořením alarmu.")
                if (colorConfidence < 0.45f) add("Barva útoku má nízkou jistotu.")
            }

            AttackCandidate(
                id = "$hash-$index-${parsed.arrival?.epochSecond ?: 0}",
                rowBounds = Rect(row),
                targetVillage = target,
                absoluteArrivalText = OcrField(absoluteText, parsed.absoluteConfidence),
                relativeArrivalText = OcrField(relativeText, parsed.relativeConfidence),
                arrivalInstant = parsed.arrival,
                color = color,
                colorConfidence = colorConfidence,
                crown = crown,
                crownScore = crownScore,
                kind = kind,
                validation = parsed.validation,
                warnings = warnings,
                sourceScreenshotHash = hash
            )
        }
    }

    private fun detectAbsoluteText(text: String, times: List<String>): String {
        return times.last()
    }

    private fun detectRelativeText(text: String, times: List<String>): String {
        return if (times.size >= 2) times.first() else ""
    }

    private fun detectTargetVillage(tokens: List<OcrToken>, imageWidth: Int): OcrField {
        if (tokens.isEmpty()) return OcrField("", 0f)
        val timeTokens = tokens.filter { timeRegex.containsMatchIn(it.text) }.map { it.bounds }
        val leftCandidates = tokens.filter { token ->
            token.bounds.left < imageWidth * 0.70f && !timeRegex.containsMatchIn(token.text) &&
                token.text.length >= 2 && token.text.any { it.isLetterOrDigit() }
        }
        val coord = leftCandidates.firstOrNull { Regex("\\d{3}\\|\\d{3}").containsMatchIn(it.text) }
        val chosen = coord ?: leftCandidates.maxByOrNull { it.text.length }
        val confidence = when {
            chosen == null -> 0f
            coord != null -> 0.90f
            timeTokens.isNotEmpty() -> 0.72f
            else -> 0.58f
        }
        return OcrField(chosen?.text.orEmpty(), confidence, chosen?.bounds)
    }

    private fun hashBitmap(bitmap: Bitmap): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sample = ByteArray(max(1, bitmap.width * bitmap.height / 64))
        var i = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            if (i >= sample.size) break
            val p = bitmap.getPixel(x, y)
            sample[i++] = ((p shr 16) xor (p shr 8) xor p).toByte()
        }
        md.update(sample, 0, i)
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }
}
