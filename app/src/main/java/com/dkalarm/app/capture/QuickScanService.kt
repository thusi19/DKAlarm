package com.dkalarm.app.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dkalarm.app.MainActivity
import com.dkalarm.app.R
import com.dkalarm.app.alarm.AlarmScheduler
import com.dkalarm.app.alarm.ScheduleResult
import com.dkalarm.app.analysis.ScreenshotAnalyzer
import com.dkalarm.app.model.CrownState
import com.dkalarm.app.model.EditableAttack
import com.dkalarm.app.storage.AlarmStore
import com.dkalarm.app.storage.ReviewStore
import com.dkalarm.app.storage.SettingsStore
import kotlinx.coroutines.*
import java.time.Instant

class QuickScanService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzer = ScreenshotAnalyzer()
    private lateinit var scheduler: AlarmScheduler
    private lateinit var alarmStore: AlarmStore
    private lateinit var reviewStore: ReviewStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var windowManager: WindowManager

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayButton: Button? = null
    private var scanning = false
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    override fun onCreate() {
        super.onCreate()
        scheduler = AlarmScheduler(this)
        alarmStore = AlarmStore(this)
        reviewStore = ReviewStore(this)
        settingsStore = SettingsStore(this)
        windowManager = getSystemService(WindowManager::class.java)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_SCAN -> scanNow()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        if (projection != null) return
        startAsProjectionForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = readResultData(intent) ?: run { stopSelf(); return }
        if (resultCode != Activity.RESULT_OK) { stopSelf(); return }

        val metrics = resources.displayMetrics
        densityDpi = metrics.densityDpi
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width(); height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val size = android.graphics.Point()
            @Suppress("DEPRECATION") display.getRealSize(size)
            width = size.x; height = size.y
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: run {
                updateForeground("Sdílení obrazovky se nepodařilo spustit.")
                stopSelf()
                return
            }
        projection = newProjection
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupCaptureResources()
                stopSelf()
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = newProjection.createVirtualDisplay(
            "DKAlarmQuickScan", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, mainHandler
        )
        showOverlay()
        updateForeground("Rychlý scan je připraven. Otevři v DK příchozí a klepni SCAN.")
    }

    private fun scanNow() {
        if (scanning || projection == null) return
        scanning = true
        overlayButton?.visibility = android.view.View.INVISIBLE
        updateOverlayText("…")
        mainHandler.postDelayed({
            val bitmap = acquireBitmap()
            overlayButton?.visibility = android.view.View.VISIBLE
            if (bitmap == null) {
                scanning = false
                updateOverlayText("SCAN")
                showResultNotification("Scan se nepodařil", "Nebyl dostupný čerstvý obraz. Zkus SCAN znovu.", true)
                return@postDelayed
            }
            scope.launch {
                try {
                    val candidates = analyzer.analyze(bitmap, Instant.now())
                    bitmap.recycle()
                    processCandidates(
                        candidates.map { c ->
                            EditableAttack(c.id, c.targetVillage.text, c.absoluteArrivalText.text, c.arrivalInstant, c.color, c.crown, c.validation, c.warnings, c.sourceScreenshotHash)
                        },
                        candidates.map { it.needsReview }
                    )
                } catch (t: Throwable) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    showResultNotification("Analýza selhala", t.message ?: t.javaClass.simpleName, true)
                } finally {
                    scanning = false
                    mainHandler.post { updateOverlayText("SCAN") }
                }
            }
        }, 260L)
    }

    private fun processCandidates(attacks: List<EditableAttack>, needsReview: List<Boolean>) {
        if (attacks.isEmpty()) {
            showResultNotification("Nic nenalezeno", "Na obrazovce nebyly rozpoznány řádky příchozích útoků.", true)
            return
        }
        val review = mutableListOf<EditableAttack>()
        var scheduled = 0
        var duplicates = 0
        var ignored = 0
        var exactMissing = false

        attacks.forEachIndexed { index, attack ->
            val important = attack.crown == CrownState.YES || attack.color.name == "RED" || attack.color.name == "BROWN"
            if (!important && attack.crown == CrownState.NO) { ignored++; return@forEachIndexed }
            if (needsReview.getOrElse(index) { true } || attack.arrivalInstant == null || attack.crown == CrownState.MAYBE) {
                review += attack
                return@forEachIndexed
            }
            val arrival = attack.arrivalInstant ?: return@forEachIndexed
            when (scheduler.schedule(attack.targetVillage, arrival, settingsStore.leadSeconds, attack.crown, attack.color, attack.sourceScreenshotHash)) {
                is ScheduleResult.Scheduled -> scheduled++
                is ScheduleResult.Duplicate -> duplicates++
                is ScheduleResult.Invalid -> ignored++
                ScheduleResult.ExactPermissionRequired -> { exactMissing = true; review += attack }
            }
        }

        reviewStore.save(review)
        attacks.firstOrNull()?.sourceScreenshotHash?.let(alarmStore::markScreenshotSeen)
        val title = if (review.isEmpty()) "DK Alarm: scan hotový" else "DK Alarm: ${review.size}× zkontrolovat"
        val body = buildString {
            append("Alarmy $scheduled")
            if (duplicates > 0) append(", duplicitní $duplicates")
            if (ignored > 0) append(", bez alarmu $ignored")
            if (review.isNotEmpty()) append(", kontrola ${review.size}")
            if (exactMissing) append(". Povol přesné alarmy.")
        }
        showResultNotification(title, body, review.isNotEmpty())
    }

    private fun acquireBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            return cropped
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayButton != null) return
        val button = Button(this).apply {
            text = "SCAN"
            isAllCaps = true
            setOnClickListener { scanNow() }
            setOnLongClickListener { stopSelf(); true }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 20; y = 0
        }
        windowManager.addView(button, params)
        overlayButton = button
    }

    private fun updateOverlayText(text: String) { overlayButton?.text = text }

    private fun startAsProjectionForeground() {
        val notification = serviceNotification("Připravuji rychlý scan…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateForeground(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, serviceNotification(text))
    }

    private fun serviceNotification(text: String): Notification {
        val stop = PendingIntent.getService(this, 10, Intent(this, QuickScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("DK Alarm – rychlý scan")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Ukončit", stop)
            .build()
    }

    private fun showResultNotification(title: String, body: String, openApp: Boolean) {
        val open = PendingIntent.getActivity(
            this, 12,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
        if (openApp) builder.setContentIntent(open)
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "DK rychlý scan", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, "Výsledky DK scanu", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun cleanupCaptureResources() {
        overlayButton?.let { runCatching { windowManager.removeView(it) } }
        overlayButton = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
    }

    override fun onDestroy() {
        val activeProjection = projection
        projection = null
        cleanupCaptureResources()
        runCatching { activeProjection?.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun readResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)

    companion object {
        const val ACTION_START = "com.dkalarm.app.capture.START"
        const val ACTION_SCAN = "com.dkalarm.app.capture.SCAN"
        const val ACTION_STOP = "com.dkalarm.app.capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_SERVICE = "dk_quick_scan_service"
        private const val CHANNEL_RESULTS = "dk_scan_results"
        private const val NOTIFICATION_ID = 8810
        private const val RESULT_NOTIFICATION_ID = 8811

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, QuickScanService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
