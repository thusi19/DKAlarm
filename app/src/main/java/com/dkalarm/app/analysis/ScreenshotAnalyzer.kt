package com.dkalarm.app.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.dkalarm.app.core.Rules
import com.dkalarm.app.core.TableGeometry
import com.dkalarm.app.core.TableParser
import com.dkalarm.app.model.Attack
import com.dkalarm.app.model.GAME_ZONE
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenshotAnalyzer {
    suspend fun analyze(bitmap: Bitmap, date: LocalDate, progress: (Int, Int) -> Unit = { _, _ -> }): List<Attack> = withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val geometry = TableGeometry.detect(pixels, bitmap.width, bitmap.height)
        check(geometry.valid) { "Sloupce tabulky se nepodařilo bezpečně oddělit. Použij přehled příchozích útoků s béžovou tabulkou; zahrň sloupce Povel, Cíl a Příchod." }
        val digest = MessageDigest.getInstance("SHA-256")
        pixels.forEach { p -> digest.update((p shr 16).toByte()); digest.update((p shr 8).toByte()); digest.update(p.toByte()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val parser = TableParser()
        val result = mutableListOf<Attack>()
        try {
            geometry.bands.forEachIndexed { index, band ->
                coroutineContext.ensureActive()
                val scale = 4f
                val pad = 12
                val scaled = Bitmap.createBitmap((bitmap.width * scale).toInt(), ((band.bottom-band.top)*scale).toInt()+pad*2, Bitmap.Config.ARGB_8888)
                Canvas(scaled).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, Rect(0, band.top, bitmap.width, band.bottom), Rect(0,pad,scaled.width,scaled.height-pad), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val words = try {
                    suspendCancellableCoroutine<List<TableParser.Word>> { continuation ->
                        recognizer.process(InputImage.fromBitmap(scaled, 0))
                            .addOnSuccessListener { text ->
                                val tokens = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
                                    element.boundingBox?.let { b -> TableParser.Word(element.text,
                                        (b.left/scale).toInt(), ((b.top-pad)/scale).toInt()+band.top,
                                        (b.right/scale).toInt(), ((b.bottom-pad)/scale).toInt()+band.top,
                                        element.confidence ?: -1f) }
                                }
                                if (continuation.isActive) continuation.resume(tokens)
                            }.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                            .addOnCompleteListener { scaled.recycle() }
                    }
                } catch (e: Exception) { throw e }
                val row = parser.parseRow(words, geometry, band, date, GAME_ZONE)
                if (row != null) {
                    val color = detectFlag(bitmap, row)
                    val warning = buildList {
                        if (row.warning.isNotBlank()) add(row.warning.trim())
                        if (color == Rules.Color.UNKNOWN) add("Barva NEJISTÁ / ZKONTROLOVAT.")
                    }.joinToString(" ")
                    result += Attack("$hash-$index", row.villageName, row.coordinates, row.arrival,
                        row.noble == Rules.Noble.YES, row.noble, color, warning,
                        reviewed = warning.isBlank(), rawCommand = row.commandText, rawArrival = row.arrivalText,
                        sourceHash = hash, rowTop = band.top, rowBottom = band.bottom)
                }
                progress(index+1, geometry.bands.size)
            }
        } finally { recognizer.close() }
        result
    }

    private fun detectFlag(bitmap: Bitmap, row: TableParser.Row): Rules.Color {
        var green = 0; var red = 0
        val hsv = FloatArray(3)
        // Beige backgrounds, brown text, pencil and crown are not reliable brown-flag evidence.
        for (y in row.top.coerceAtLeast(0) until row.bottom.coerceAtMost(bitmap.height)) {
            for (x in row.flagLeft.coerceAtLeast(0) until row.flagRight.coerceAtMost(bitmap.width)) {
                Color.colorToHSV(bitmap.getPixel(x,y), hsv)
                if (hsv[1] < .48f || hsv[2] < .25f || hsv[2] > .92f) continue
                if (hsv[0] in 65f..155f) green++
                if (hsv[0] < 15 || hsv[0] > 345) red++
            }
        }
        val minimum = maxOf(3,(row.bottom-row.top)/4)
        return when {
            green >= minimum && green >= red*3 -> Rules.Color.GREEN
            red >= minimum && red >= green*3 -> Rules.Color.RED
            else -> Rules.Color.UNKNOWN // Brown must be confirmed; no brown positive sample yet.
        }
    }
}
