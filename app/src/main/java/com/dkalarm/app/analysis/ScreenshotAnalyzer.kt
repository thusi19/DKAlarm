package com.dkalarm.app.analysis

import android.graphics.*
import com.dkalarm.app.core.Rules
import com.dkalarm.app.core.TableGeometry
import com.dkalarm.app.core.TableParser
import com.dkalarm.app.model.Attack
import com.dkalarm.app.model.GAME_ZONE
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
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
        val g = TableGeometry.detect(pixels, bitmap.width, bitmap.height)
        check(g.valid) { "Sloupce tabulky se nepodařilo bezpečně oddělit. Zahrň sloupce Povel, Cíl a Příchod." }
        val digest = MessageDigest.getInstance("SHA-256")
        pixels.forEach { p -> digest.update((p shr 16).toByte()); digest.update((p shr 8).toByte()); digest.update(p.toByte()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val result = mutableListOf<Attack>()
        try {
            val firstBands=g.bands.take(12)
            val anchors=read(recognizer,bitmap,Rect(g.left+((g.right-g.left)*.66f).toInt(),firstBands.first().top,g.right,firstBands.last().bottom),3f,false)
                .filter { Rules.normalize(it.text).matches(Regex("(dnes|zitra|dne).*")) }.map { it.left }.sorted()
            if(anchors.size>=2) {
                g.arrivalStart=anchors[anchors.size/2]-3
                if(g.arrivalEnd<=g.arrivalStart)g.arrivalEnd=g.right
            }
            // Column strips preserve line context without mixing origin villages with commands.
            for (bands in g.bands.chunked(12)) {
                coroutineContext.ensureActive()
                val top = bands.first().top
                val bottom = bands.last().bottom
                val rowHeight = bands.map { it.bottom-it.top }.sorted()[bands.size/2]
                val scale = (110f / rowHeight).coerceIn(2f, 7f)
                val command = read(recognizer, bitmap, Rect(g.left,top,g.targetStart-1,bottom),scale,false)
                val cleanCommand = read(recognizer, bitmap, Rect(g.left,top,g.targetStart-1,bottom),scale,true)
                val target = read(recognizer, bitmap, Rect(g.targetStart+2,top,g.sourceStart-1,bottom),scale,false)
                val arrival = read(recognizer, bitmap, Rect(g.arrivalStart+2,top,g.arrivalEnd-1,bottom),scale,false)
                for (band in bands) {
                    fun rowWords(words: List<TableParser.Word>) = words.filter { (it.top+it.bottom)/2 in band.top until band.bottom }
                    val raw = rowWords(command)
                    val clean = rowWords(cleanCommand)
                    val cleanText = clean.sortedBy { it.left }.joinToString(" ") { it.text }
                    val rawText = raw.sortedBy { it.left }.joinToString(" ") { it.text }
                    val evidence = Rules.nobleEvidence(cleanText,rawText)
                    val chosen = if(evidence == Rules.Noble.YES) clean else raw
                    val row = TableParser().parseRow(chosen+rowWords(target)+rowWords(arrival),g,band,date,GAME_ZONE) ?: continue
                    if(evidence==Rules.Noble.YES) {
                        row.noble=Rules.Noble.YES
                        row.warning=row.warning.replace(" Text jednotky je NEJISTÝ / ZKONTROLOVAT.", "")
                    }
                    android.util.Log.i("DK_WORD", "${band.top} target="+rowWords(target).joinToString(" "){it.text})
                    val color = detectFlag(bitmap,g,band,row)
                    val warning = buildList {
                        if(row.warning.isNotBlank()) add(row.warning.trim())
                        if(color==Rules.Color.UNKNOWN) add("Barva NEJISTÁ / ZKONTROLOVAT.")
                    }.joinToString(" ")
                    result += Attack("$hash-${band.top}",row.villageName,row.coordinates,row.arrival,
                        row.noble==Rules.Noble.YES,row.noble,color,warning,reviewed=warning.isBlank(),
                        rawCommand="$rawText / $cleanText",rawArrival=row.arrivalText,sourceHash=hash,rowTop=band.top,rowBottom=band.bottom)
                    progress(result.size,g.bands.size)
                }
            }
        } finally { recognizer.close() }
        result
    }

    private suspend fun read(recognizer:TextRecognizer, bitmap:Bitmap, rect:Rect, scale:Float, textOnly:Boolean):List<TableParser.Word> {
        val pad=24
        val crop=Bitmap.createBitmap(bitmap,rect.left,rect.top,rect.width(),rect.height()).copy(Bitmap.Config.ARGB_8888,true)
        if(textOnly) {
            // Brown command lettering survives; grey crowns and colored flags do not become letters.
            val p=IntArray(crop.width*crop.height)
            crop.getPixels(p,0,crop.width,0,0,crop.width,crop.height)
            for(i in p.indices) {
                val r=Color.red(p[i]);val g=Color.green(p[i]);val b=Color.blue(p[i])
                p[i]=if(r in 40..190 && r>g*1.18 && g>b*1.12) Color.BLACK else Color.WHITE
            }
            crop.setPixels(p,0,crop.width,0,0,crop.width,crop.height)
        }
        val scaled=Bitmap.createBitmap((crop.width*scale).toInt()+pad*2,(crop.height*scale).toInt()+pad*2,Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.WHITE)
            drawBitmap(crop,null,Rect(pad,pad,scaled.width-pad,scaled.height-pad),Paint(Paint.FILTER_BITMAP_FLAG))
        }
        crop.recycle()
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(scaled,0)).addOnSuccessListener { text ->
                val words=text.textBlocks.flatMap {it.lines}.flatMap {it.elements}.mapNotNull { e ->
                    e.boundingBox?.let { b -> TableParser.Word(e.text,
                        ((b.left-pad)/scale).toInt()+rect.left,((b.top-pad)/scale).toInt()+rect.top,
                        ((b.right-pad)/scale).toInt()+rect.left,((b.bottom-pad)/scale).toInt()+rect.top,e.confidence ?: -1f) }
                }
                if(continuation.isActive)continuation.resume(words)
            }.addOnFailureListener { if(continuation.isActive)continuation.resumeWithException(it) }
             .addOnCompleteListener {scaled.recycle()}
        }
    }

    private fun detectFlag(bitmap:Bitmap,g:TableGeometry,band:TableGeometry.Band,row:TableParser.Row):Rules.Color {
        val h=band.bottom-band.top
        // Only the first flag area; never the crown, label or pencil to its right.
        val cap = g.left+((g.targetStart-g.left)*.43f).toInt()
        val end = minOf(cap,row.flagRight-(if(row.noble==Rules.Noble.YES) h/2 else 0)).coerceAtLeast(g.left)
        var green=0;var red=0;var brown=0
        val hsv=FloatArray(3)
        for(y in (band.top+h/6) until (band.bottom-h/6)) for(x in g.left until end) {
            Color.colorToHSV(bitmap.getPixel(x,y),hsv)
            if(hsv[1]<.55f || hsv[2]<.22f || hsv[2]>.78f)continue
            when {
                hsv[0] in 65f..155f -> green++
                hsv[0]<15 || hsv[0]>345 -> red++
                hsv[0] in 18f..45f -> brown++
            }
        }
        val min=maxOf(3,h/4)
        return when {
            green>=min && green>=red*3 && green>=brown*2 -> Rules.Color.GREEN
            red>=min && red>=green*3 && red>=brown*2 -> Rules.Color.RED
            brown>=min && brown>=red*3 && brown>=green*3 -> Rules.Color.BROWN
            else -> Rules.Color.UNKNOWN
        }
    }
}
