package com.dkalarm.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.dkalarm.app.MainActivity
import com.dkalarm.app.R
import com.dkalarm.app.storage.AlarmStore

interface UnusedFormatPlaceholder

class AlarmSoundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var key: String = ""

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "DK alarmy", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Přesné alarmy na důležité příchozí útoky"
                enableVibration(true)
                setSound(null, null)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = intent?.getStringExtra("type") ?: "RED"
        val target = intent?.getStringExtra("target").orEmpty()
        key = intent?.getStringExtra("key").orEmpty()
        val noble = type == "NOBLE"

        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (noble) "👑 ŠLECHTA – DK Alarm" else "⚠️ Důležitý útok – DK Alarm")
            .setContentText(if (target.isBlank()) "Útok brzy dopadne." else "$target – útok brzy dopadne.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        stopToneOnly()
        tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        playPattern(noble)
        return START_NOT_STICKY
    }

    private fun playPattern(noble: Boolean) {
        val pulses = if (noble) 12 else 6
        val intervalMs = if (noble) 650L else 1_500L
        val toneType = if (noble) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2
        val durationMs = if (noble) 450 else 700
        repeat(pulses) { i -> handler.postDelayed({ tone?.startTone(toneType, durationMs) }, i * intervalMs) }
        handler.postDelayed({ finishAlarm() }, pulses * intervalMs + 900L)
    }

    private fun stopToneOnly() { handler.removeCallbacksAndMessages(null); tone?.stopTone(); tone?.release(); tone = null }
    private fun finishAlarm() { stopToneOnly(); if (key.isNotBlank()) AlarmStore(this).remove(key); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { stopToneOnly(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val CHANNEL = "dk_alarm_critical"; const val NOTIFICATION_ID = 7331 }
}
