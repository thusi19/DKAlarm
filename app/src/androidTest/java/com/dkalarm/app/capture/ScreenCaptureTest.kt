package com.dkalarm.app.capture

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.dkalarm.app.MainActivity
import com.dkalarm.app.analysis.ScreenshotAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class ScreenCaptureTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
    private fun waitFor(message:String,timeout:Long=20_000,condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(100)}
        device.dumpWindowHierarchy(File(context.cacheDir,"capture-test-ui.xml"))
        error(message)
    }
    private fun consent() {
        val mode=device.wait(Until.findObject(By.res(Pattern.compile("com.android.systemui:id/screen_share_mode_(spinner|options)"))),7_000)
        if(mode!=null) {
            mode.click()
            val entire=device.wait(Until.findObject(By.text(Pattern.compile("(?i).*entire screen.*"))),5_000)
            if(entire!=null)entire.click()
        }
        val start=device.wait(Until.findObject(By.res("android:id/button1")),5_000)
            ?: device.wait(Until.findObject(By.text(Pattern.compile("(?i)(start now|start|start sharing|share screen|share)"))),5_000)
        checkNotNull(start){"Android consent button was not found"}
        start.click()
    }
    @Test fun fullResolutionOneShotFeedsTheSameOcr()=runBlocking {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val before=CaptureStore.latest(context)?.id
        instrumentation.context.assets.open("new-05.png").use {input ->
            File(context.cacheDir,"capture-fixture.png").outputStream().use {input.copyTo(it)}
        }
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {it.requestScreenCapture()}
            consent()
            waitFor("Projection was not prepared"){CaptureStore.isActive(context)}
            context.startActivity(Intent().setClassName(context.packageName,CaptureFixtureActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            checkNotNull(device.wait(Until.findObject(By.desc("DK_CAPTURE_FIXTURE")),10_000))
            waitFor("Fixture must be in landscape"){device.displayWidth>device.displayHeight}
            val nativeWidth=device.displayWidth;val nativeHeight=device.displayHeight
            // Same direct Activity used by the Sejmout notification action.
            context.startActivity(Intent(context,CaptureTriggerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("No captured PNG was delivered",30_000){CaptureStore.latest(context)?.id?.let {it!=before}==true}
            val shot=CaptureStore.latest(context)!!
            assertEquals(nativeWidth,shot.width);assertEquals(nativeHeight,shot.height)
            assertFalse("Projection must stop after one shot",CaptureStore.isActive(context))
            val pixels=BitmapFactory.decodeFile(shot.file.absolutePath)
            try {
                assertEquals(shot.width,pixels.width);assertEquals(shot.height,pixels.height)
                val rows=ScreenshotAnalyzer().analyze(pixels,shot.date)
                android.util.Log.i("DK_CAPTURE","Captured ${pixels.width}x${pixels.height}; rows=${rows.size}; nobles=${rows.count {it.isNoble}}")
                assertTrue(rows.size>=10)
                assertEquals("Known text on a real screen capture",3,rows.count {it.isNoble})
                assertTrue(rows.filter {it.isNoble}.all {it.coordinates=="555|417"})
            } finally {pixels.recycle()}
        }
        device.pressBack()
    }
    @Test fun cancelledPermissionDoesNotCapture() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val before=CaptureStore.latest(context)?.id
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {it.requestScreenCapture()}
            val cancel=device.wait(Until.findObject(By.res("android:id/button2")),7_000)
                ?: device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel"))),5_000)
            checkNotNull(cancel).click()
            waitFor("Cancel must return to app"){device.currentPackageName==context.packageName}
            assertFalse(CaptureStore.isActive(context))
            assertEquals(before,CaptureStore.latest(context)?.id)
        }
    }
}
