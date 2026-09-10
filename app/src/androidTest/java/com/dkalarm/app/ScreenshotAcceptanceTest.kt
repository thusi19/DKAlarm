package com.dkalarm.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.dkalarm.app.analysis.ScreenshotAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Release gate: real bundled ML Kit, not the Tesseract development approximation. */
class ScreenshotAcceptanceTest {
    private fun analyze(name:String,day:Int) = runBlocking {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap=assets.open(name).use {BitmapFactory.decodeStream(it)}
        try {
            ScreenshotAnalyzer().analyze(bitmap,LocalDate.of(2026,9,day)).also { rows ->
                rows.forEach { a -> android.util.Log.i("DK_OCR", "$name ROW ${a.rowTop}: ${a.rawCommand} | ${a.villageName} | ${a.coordinates} | ${a.rawArrival} | ${a.isNoble} | ${a.arrivalTime}") }
                println("$name rows=${rows.size} nobles=${rows.count {it.isNoble}}")
            }
        }
        finally {bitmap.recycle()}
    }
    @Test fun negativeNoNobles() {
        for(file in listOf("03-1000003625.png","04-1000003623.png")) {
            val rows=analyze(file,9)
            assertTrue("A negative test must not pass by finding no rows",rows.size>=35)
            assertEquals("No confirmed nobles: $file",0,rows.count {it.isNoble})
        }
    }
    @Test fun zoomedPositiveFourNobles() {
        val nobles=analyze("02-1000003831.png",10).filter {it.isNoble}
        assertEquals(4,nobles.size)
        assertTrue(nobles.all {it.coordinates=="559|424"})
        assertTrue(nobles.all {it.arrivalTime!=null})
    }
    @Test fun portraitPositiveTwelveNobles() {
        val nobles=analyze("01-1000003833.png",10).filter {it.isNoble}
        assertEquals(12,nobles.size)
        assertEquals(4,nobles.count {it.coordinates=="558|423"})
        assertEquals(4,nobles.count {it.coordinates=="560|424"})
        assertEquals(4,nobles.count {it.coordinates=="559|424"})
        assertTrue(nobles.all {it.arrivalTime!=null})
    }
}
