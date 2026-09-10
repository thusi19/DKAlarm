package com.dkalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key=intent.getStringExtra("key") ?: return
        val scheduler=AlarmScheduler(context)
        val slot=scheduler.slots().firstOrNull { it.key==key } ?: return
        // A stale PendingIntent cannot ring after a cancellation, edit or suppression change.
        if (slot.trigger.isAfter(Instant.now().plusSeconds(2))) return
        if (slot.attacks.all { it.arrivalTime!!.isBefore(Instant.now()) }) return
        context.startForegroundService(Intent(context,AlarmSoundService::class.java).putExtra("key",key))
    }
}
