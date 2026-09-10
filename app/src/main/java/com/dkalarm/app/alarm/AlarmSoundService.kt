package com.dkalarm.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dkalarm.app.MainActivity
import com.dkalarm.app.R
import com.dkalarm.app.model.AlarmSlot
import com.dkalarm.app.storage.AlarmStore
import java.time.Instant

class AlarmSoundService : Service() {
    private var player: MediaPlayer? = null
    private var playingNoble=false
    private var wakeLock: PowerManager.WakeLock? = null
    private val active=linkedMapOf<String,AlarmSlot>()
    private val handler=Handler(Looper.getMainLooper())
    private val finish=Runnable { finishAlarm() }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"DK Alarm – útoky",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Jméno vesnice, typ útoku a čas příchodu"; setSound(null,null); enableVibration(true)
            })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="STOP") { finishAlarm();return START_NOT_STICKY }
        val slot=if(intent?.action=="TEST") AlarmSlot("test",Instant.now(),listOf(
            com.dkalarm.app.model.Attack("test","Testovací vesnice","",Instant.now().plusSeconds(60),true,
                com.dkalarm.app.core.Rules.Noble.YES,com.dkalarm.app.core.Rules.Color.GREEN)))
        else AlarmScheduler(this).slots().firstOrNull { it.key==intent?.getStringExtra("key") }
        if(slot==null) { stopSelf(startId);return START_NOT_STICKY }
        active[slot.key]=slot
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,AlarmSoundService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val lines=active.values.map { "${it.title}\nPříchod: ${it.arrival}" }
        val notification=NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if(active.size==1)slot.title else "DK Alarm – ${active.size} souběžných alarmů")
            .setContentText("Příchod: ${slot.arrival}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n\n")))
            .setContentIntent(open).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
            .addAction(0,"Ztišit",stop).build()
        startForeground(NOTIFICATION_ID,notification)
        if(slot.key!="test") AlarmStore(this).fired(slot.attacks.map { it.identity }.toSet())
        if(wakeLock==null)wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"dkalarm:audio")
        if(wakeLock?.isHeld!=true)wakeLock?.acquire(90_000)
        val noble=active.values.any { it.noble }
        if(player==null||noble&&!playingNoble) {
            player?.release();playingNoble=noble
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            player=MediaPlayer.create(this,if(noble)R.raw.noble_alarm else R.raw.attack_alarm,attributes,0)
            player?.apply { isLooping=true;start() }
        }
        handler.removeCallbacks(finish);handler.postDelayed(finish,60_000)
        return START_NOT_STICKY
    }
    private fun finishAlarm() {
        handler.removeCallbacks(finish);player?.release();player=null
        if(wakeLock?.isHeld==true)wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);player?.release();player=null;if(wakeLock?.isHeld==true)wakeLock?.release();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object { const val CHANNEL="dk_text_critical_v3";const val NOTIFICATION_ID=7331 }
}
