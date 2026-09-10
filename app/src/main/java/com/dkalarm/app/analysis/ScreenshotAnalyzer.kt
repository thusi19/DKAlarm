package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import com.dkalarm.app.model.AttackCandidate
import com.dkalarm.app.model.OcrField
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ScreenshotAnalyzer(
    private val ocrEngine: OcrEngine = MlKitOcrEngine(),
    private val rowDetector: RowDetector = HeuristicRowDetector(),
    private val colorDetector: ColorDetector = HsvColorDetector(),
    private val crownDetector: CrownDetector = HighRecallCrownDetector(),
    private val classifier: AttackClassifier = AttackClassifier(),
    private val timeValidator: TimeValidator = TimeValidator()
) {
    private val absoluteTimeRegex = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?!\\d)")
    private val durationRegex = Regex("(?<!\\d)\\d{1,3}:[0-5]\\d:[0-5]\\d(?!\\d)")
    private val coordinateRegex = Regex("\\(?\\s*\\d{3}\\s*[|/]\\s*\\d{3}\\s*\\)?")

    suspend fun analyze(bitmap: Bitmap, capturedAt: Instant = Instant.now()): List<AttackCandidate> {
        val hash = hashBitmap(bitmap)
        val ocr = ocrEngine.recognize(bitmap)
        val layout = detectTableLayout(ocr.tokens, bitmap.width, bitmap.height)
        val rows = if (layout != null) {
            detectTableRows(ocr.tokens, layout, bitmap.width, bitmap.height)
        } else {
            rowDetector.detect(ocr.tokens, bitmap.width, bitmap.height)
        }

        return rows.mapIndexedNotNull { index, row ->
            val rowTokens = ocr.tokens.filter { token -> token.bounds.centerY() in row.top..row.bottom }
            if (rowTokens.isEmpty()) return@mapIndexedNotNull null

            val fallbackText = rowTokens.sortedBy { it.bounds.left }.joinToString(" ") { it.text }
            val absoluteSource = layout?.let { columnText(rowTokens, it.arrival) } ?: fallbackText
            val relativeSource = layout?.let { columnText(rowTokens, it.relative) } ?: fallbackText

            val absoluteText = absoluteTimeRegex.find(absoluteSource)?.value
                ?: absoluteTimeRegex.find(fallbackText)?.value
                ?: return@mapIndexedNotNull null

            val relativeText = if (layout != null) {
                durationRegex.find(relativeSource)?.value.orEmpty()
            } else {
                val all = durationRegex.findAll(fallbackText).map { it.value }.toList()
                if (all.size >= 2) all.first() else ""
            }

            // Pass the whole arrival-cell text as well, so an explicit DK date such as 11.09.
            // can be used when OCR found it.
            val parsed = timeValidator.parse(absoluteSource, relativeText, capturedAt)
            val target = if (layout != null) detectTargetVillage(rowTokens, layout.target)
                else detectTargetVillageFallback(rowTokens, bitmap.width)

            val commandArea = layout?.let { Rect(it.command.left, row.top, it.command.right, row.bottom) }
            val (color, colorConfidence) = colorDetector.detect(bitmap, row, commandArea)
            val (crown, crownScore) = crownDetector.detect(bitmap, row, commandArea)
            val kind = classifier.classify(crown, color)

            val warnings = buildList {
                addAll(parsed.warnings)
                if (layout == null) add("Nepodařilo se jistě najít záhlaví DK tabulky; použit záložní režim.")
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

    private data class ColumnRange(val left: Int, val right: Int) {
        fun containsX(x: Int): Boolean = x in left..right
    }

    private data class TableLayout(
        val headerY: Int,
        val command: ColumnRange,
        val target: ColumnRange,
        val arrival: ColumnRange,
        val relative: ColumnRange
    )

    private fun detectTableLayout(tokens: List<OcrToken>, imageWidth: Int, imageHeight: Int): TableLayout? {
        data class Header(val key: String, val token: OcrToken)

        val headers = tokens.mapNotNull { token -> headerKey(token.text)?.let { Header(it, token) } }
        if (headers.isEmpty()) return null

        val tolerance = max(8, imageHeight / 180)
        val groups = mutableListOf<MutableList<Header>>()
        for (header in headers.sortedBy { it.token.bounds.centerY() }) {
            val last = groups.lastOrNull()
            val avg = last?.map { it.token.bounds.centerY() }?.average()
            if (last == null || avg == null || abs(avg - header.token.bounds.centerY()) > tolerance) {
                groups += mutableListOf(header)
            } else {
                last += header
            }
        }

        val best = groups.maxByOrNull { group -> group.map { it.key }.distinct().size } ?: return null
        val keys = best.map { it.key }.toSet()
        if (!("cil" in keys && "puvod" in keys && "prichod" in keys && "dorazi" in keys)) return null

        val headerY = best.map { it.token.bounds.centerY() }.average().toInt()
        fun center(key: String): Int? = best.filter { it.key == key }
            .minByOrNull { abs(it.token.bounds.centerY() - headerY) }
            ?.token?.bounds?.centerX()

        val commandCenter = center("povel") ?: 24
        val targetCenter = center("cil") ?: return null
        val originCenter = center("puvod") ?: return null
        val arrivalCenter = center("prichod") ?: return null
        val relativeCenter = center("dorazi") ?: return null
        val distanceCenter = center("vzdalenost")
            ?: (arrivalCenter - max(20, relativeCenter - arrivalCenter))
        val watchCenter = center("strazni")
            ?: (relativeCenter + max(20, relativeCenter - arrivalCenter))

        val targetLeft = midpoint(commandCenter, targetCenter).coerceIn(0, imageWidth - 1)
        val targetRight = midpoint(targetCenter, originCenter).coerceIn(targetLeft + 1, imageWidth)
        val arrivalLeft = midpoint(distanceCenter, arrivalCenter).coerceIn(0, imageWidth - 1)
        val arrivalRight = midpoint(arrivalCenter, relativeCenter).coerceIn(arrivalLeft + 1, imageWidth)
        val relativeRight = midpoint(relativeCenter, watchCenter).coerceIn(arrivalRight + 1, imageWidth)

        return TableLayout(
            headerY = headerY,
            command = ColumnRange(0, targetLeft),
            target = ColumnRange(targetLeft, targetRight),
            arrival = ColumnRange(arrivalLeft, arrivalRight),
            relative = ColumnRange(arrivalRight, relativeRight)
        )
    }

    private fun detectTableRows(
        tokens: List<OcrToken>,
        layout: TableLayout,
        imageWidth: Int,
        imageHeight: Int
    ): List<Rect> {
        val anchors = tokens.filter { token ->
            token.bounds.centerY() > layout.headerY + 4 &&
                layout.arrival.containsX(token.bounds.centerX()) &&
                absoluteTimeRegex.containsMatchIn(token.text)
        }.sortedBy { it.bounds.centerY() }

        if (anchors.isEmpty()) return emptyList()
        val typicalTokenHeight = anchors.map { it.bounds.height().coerceAtLeast(1) }.sorted().let { it[it.size / 2] }
        val yTolerance = max(4, typicalTokenHeight)

        val centerGroups = mutableListOf<MutableList<OcrToken>>()
        for (anchor in anchors) {
            val last = centerGroups.lastOrNull()
            val avg = last?.map { it.bounds.centerY() }?.average()
            if (last == null || avg == null || abs(avg - anchor.bounds.centerY()) > yTolerance) {
                centerGroups += mutableListOf(anchor)
            } else {
                last += anchor
            }
        }

        val centers = centerGroups.map { group -> group.map { it.bounds.centerY() }.average().toInt() }.sorted()
        val spacings = centers.zipWithNext { a, b -> b - a }.filter { it in 6..80 }.sorted()
        val spacing = if (spacings.isNotEmpty()) spacings[spacings.size / 2] else max(18, typicalTokenHeight * 2)
        val halfHeight = max(7, min(30, (spacing * 0.46f).toInt()))

        return centers.map { cy ->
            Rect(0, max(layout.headerY + 2, cy - halfHeight), imageWidth, min(imageHeight, cy + halfHeight))
        }
    }

    private fun detectTargetVillage(tokens: List<OcrToken>, range: ColumnRange): OcrField {
        val cell = tokens.filter { range.containsX(it.bounds.centerX()) }.sortedBy { it.bounds.left }
        if (cell.isEmpty()) return OcrField("", 0f)
        val text = cell.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
        val hasCoordinate = coordinateRegex.containsMatchIn(text.replace(" ", "")) || coordinateRegex.containsMatchIn(text)
        val bounds = unionBounds(cell)
        return OcrField(text, if (hasCoordinate) 0.94f else 0.66f, bounds)
    }

    private fun detectTargetVillageFallback(tokens: List<OcrToken>, imageWidth: Int): OcrField {
        if (tokens.isEmpty()) return OcrField("", 0f)
        val leftCandidates = tokens.filter { token ->
            token.bounds.left < imageWidth * 0.70f &&
                !durationRegex.containsMatchIn(token.text) &&
                token.text.length >= 2 && token.text.any { it.isLetterOrDigit() }
        }
        val joined = leftCandidates.sortedBy { it.bounds.left }.joinToString(" ") { it.text }
        val coord = coordinateRegex.find(joined)?.value
        if (coord != null) return OcrField(joined, 0.86f, unionBounds(leftCandidates))
        val chosen = leftCandidates.maxByOrNull { it.text.length }
        return OcrField(chosen?.text.orEmpty(), if (chosen != null) 0.58f else 0f, chosen?.bounds)
    }

    private fun columnText(tokens: List<OcrToken>, range: ColumnRange): String = tokens
        .filter { range.containsX(it.bounds.centerX()) }
        .sortedBy { it.bounds.left }
        .joinToString(" ") { it.text }
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun unionBounds(tokens: List<OcrToken>): Rect? {
        val first = tokens.firstOrNull() ?: return null
        val out = Rect(first.bounds)
        tokens.drop(1).forEach { out.union(it.bounds) }
        return out
    }

    private fun midpoint(a: Int, b: Int): Int = (a + b) / 2

    private fun headerKey(text: String): String? {
        val n = normalize(text)
        return when {
            n.startsWith("povel") -> "povel"
            n == "cil" || n.startsWith("cil") -> "cil"
            n.startsWith("puvod") -> "puvod"
            n.startsWith("hrac") -> "hrac"
            n.startsWith("vzdalen") -> "vzdalenost"
            n.startsWith("prichod") -> "prichod"
            n.startsWith("dorazi") -> "dorazi"
            n.startsWith("strazni") -> "strazni"
            else -> null
        }
    }

    private fun normalize(text: String): String = Normalizer.normalize(
        text.lowercase(Locale.ROOT), Normalizer.Form.NFD
    ).replace(Regex("\\p{Mn}+"), "")

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
