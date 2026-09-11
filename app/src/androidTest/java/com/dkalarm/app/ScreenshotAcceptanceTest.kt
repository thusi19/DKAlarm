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
                rows.forEach { a -> android.util.Log.i("DK_OCR", "$name ROW ${a.rowTop}: ${a.rawCommand} | ${a.villageName} | ${a.coordinates} | ${a.rawArrival} | ${a.isNoble} | ${a.arrivalTime} | ${a.attackColor}") }
                println("$name rows=${rows.size} nobles=${rows.count {it.isNoble}}")
            }
        }
        finally {bitmap.recycle()}
    }
    private fun nobleTimes(rows:List<com.dkalarm.app.model.Attack>) = rows.filter {it.isNoble}.map {
        it.arrivalTime?.atZone(com.dkalarm.app.model.GAME_ZONE)?.toLocalTime()?.toString()
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
        assertEquals(listOf("14:35:55","14:35:56","14:35:56","14:35:56"),nobleTimes(nobles))
        assertTrue(nobles.all {it.coordinates=="559|424"})
        assertTrue(nobles.all {it.arrivalTime!=null})
    }
    @Test fun portraitPositiveTwelveNobles() {
        val nobles=analyze("01-1000003833.png",10).filter {it.isNoble}
        assertEquals(12,nobles.size)
        assertEquals(listOf("14:35:55","14:35:56","14:35:56","14:35:56","15:20:43","15:20:43","15:20:44","15:20:44","15:23:14","15:23:14","15:23:14","15:23:14"),nobleTimes(nobles))
        assertEquals(4,nobles.count {it.coordinates=="558|423"})
        assertEquals(4,nobles.count {it.coordinates=="560|424"})
        assertEquals(4,nobles.count {it.coordinates=="559|424"})
        assertTrue(nobles.all {it.arrivalTime!=null})
    }
    @Test fun newPortraitTwelveNobles() {
        val rows=analyze("new-02.png",11)
        assertTrue(rows.size>=60)
        assertEquals(12,rows.count {it.isNoble})
        for(c in listOf("559|424","558|423","560|424")) assertEquals(c,4,rows.count {it.isNoble&&it.coordinates==c})
        assertTrue(rows.filter {it.isNoble}.all {it.arrivalTime!=null})
    }
    @Test fun newThreeNoblesBothSizes() {
        for(f in listOf("new-03.png","new-05.png")) {
            val rows=analyze(f,11)
            assertTrue(rows.size>=10)
            val nobles=rows.filter {it.isNoble}
            assertEquals(f,3,nobles.size)
            assertEquals(listOf("11:59:31","11:59:31","11:59:32"),nobleTimes(nobles))
            assertTrue(nobles.all {it.coordinates=="555|417"})
            assertTrue(nobles.all {it.arrivalTime!=null})
        }
    }
    @Test fun newBrownAndNegatives() {
        val none=analyze("new-01.png",11)
        assertTrue(none.size>=10)
        assertEquals(0,none.count {it.isNoble})
        val rows=analyze("new-04.png",11)
        assertEquals(0,rows.count {it.isNoble})
        val brown=rows.filter {it.attackColor==com.dkalarm.app.core.Rules.Color.BROWN}
        assertEquals(4,brown.size)
        assertTrue(brown.all {it.arrivalTime?.atZone(com.dkalarm.app.model.GAME_ZONE)?.toLocalTime()?.toString()=="11:21:22"})
        assertTrue(brown.all {it.coordinates=="557|414"})
        assertTrue(brown.all {it.arrivalTime!=null})
        assertEquals(3,rows.count {it.attackColor==com.dkalarm.app.core.Rules.Color.RED})
    }
}
