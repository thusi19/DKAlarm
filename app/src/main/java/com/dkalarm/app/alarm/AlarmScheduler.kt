package com.dkalarm.app.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dkalarm.app.MainActivity
import com.dkalarm.app.core.Rules
import com.dkalarm.app.model.AlarmSlot
import com.dkalarm.app.model.StoredAttack
import com.dkalarm.app.storage.AlarmStore
import java.time.Instant

class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val registry = context.getSharedPreferences("dk_scheduled_slots_v3", Context.MODE_PRIVATE)
    fun canScheduleExact() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    fun canNotify(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled() && nm.getNotificationChannel(AlarmSoundService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun exactAlarmSettingsIntent() = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    fun notificationSettingsIntent() = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun enabledIdentities(stored: List<StoredAttack>): Set<String> = Rules.enabled(stored.map {
        Rules.Event(it.attack.identity,it.attack.villageKey,requireNotNull(it.attack.alarmTime).epochSecond)
    })
    fun slots(stored: List<StoredAttack> = AlarmStore(context).all()): List<AlarmSlot> {
        val enabled = enabledIdentities(stored)
        return stored.filter { !it.fired && it.attack.identity in enabled }
            .groupBy { "${it.attack.villageKey}|${it.attack.alarmTime!!.epochSecond}" }
            .map { (key, values) -> AlarmSlot(key,values.first().attack.alarmTime!!,values.map { it.attack }) }
    }
    /** Recalculate using the durable full series, including suppressed and already-fired events. */
    fun reconcile() {
        val old = registry.getStringSet("keys", emptySet()).orEmpty().toSet()
        old.forEach { manager.cancel(pending(it)) }
        check(registry.edit().putStringSet("keys",emptySet()).commit())
        if (!canScheduleExact() || !canNotify()) return
        val registered = mutableSetOf<String>()
        try {
            slots().filter { it.trigger.isAfter(Instant.now()) }.forEach { slot ->
                // Persist the cancellation identity before touching AlarmManager, to survive a process kill.
                registered += slot.key
                check(registry.edit().putStringSet("keys",registered.toSet()).commit())
                val show = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(slot.trigger.toEpochMilli(), show), pending(slot.key))
            }
        } catch (e: Exception) {
            registered.forEach { manager.cancel(pending(it)) }
            registry.edit().putStringSet("keys",emptySet()).commit()
            throw e
        }
    }
    fun cancelAll() {
        registry.getStringSet("keys", emptySet()).orEmpty().forEach { manager.cancel(pending(it)) }
        registry.edit().putStringSet("keys",emptySet()).commit()
        AlarmStore(context).clear()
    }
    private fun pending(key: String): PendingIntent {
        val intent=Intent(context,AlarmReceiver::class.java).apply {
            data=Uri.Builder().scheme("dkalarm").authority("alarm").appendPath(key).build()
            putExtra("key",key)
        }
        return PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
