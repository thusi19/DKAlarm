package com.dkalarm.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dkalarm.app.model.AttackColor
import com.dkalarm.app.model.CrownState
import com.dkalarm.app.storage.AlarmStore
import com.dkalarm.app.storage.StoredAlarm
import java.time.Instant

sealed class ScheduleResult {
    data class Scheduled(val key: String) : ScheduleResult()
    data class Duplicate(val key: String) : ScheduleResult()
    data object ExactPermissionRequired : ScheduleResult()
    data class Invalid(val reason: String) : ScheduleResult()
}

class AlarmScheduler(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val store = AlarmStore(context)

    fun exactAlarmSettingsIntent(): Intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    fun schedule(
        target: String,
        arrival: Instant,
        leadSeconds: Int,
        crown: CrownState,
        color: AttackColor,
        sourceHash: String
    ): ScheduleResult {
        if (crown == CrownState.MAYBE) return ScheduleResult.Invalid("Korunka je stále nejistá.")
        val type = when {
            crown == CrownState.YES -> "NOBLE"
            color == AttackColor.RED -> "RED"
            color == AttackColor.BROWN -> "BROWN"
            else -> "NONE"
        }
        if (type == "NONE") return ScheduleResult.Invalid("Pro tento běžný útok se minutový alarm nevytváří.")
        if (!canScheduleExact()) return ScheduleResult.ExactPermissionRequired

        val trigger = arrival.minusSeconds(leadSeconds.toLong()).toEpochMilli()
        if (trigger <= System.currentTimeMillis()) return ScheduleResult.Invalid("Čas alarmu už uplynul.")
        val key = "$target|${arrival.epochSecond}|$type|$leadSeconds"
        if (store.hasAlarm(key)) return ScheduleResult.Duplicate(key)
        val requestCode = key.hashCode() and 0x7fffffff
        val pi = pendingIntent(requestCode, key, target, type, arrival.toEpochMilli())
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        store.upsert(StoredAlarm(key, requestCode, trigger, arrival.toEpochMilli(), target, type, sourceHash))
        return ScheduleResult.Scheduled(key)
    }

    fun rescheduleStored(alarm: StoredAlarm) {
        if (!canScheduleExact() || alarm.triggerAtMillis <= System.currentTimeMillis()) return
        val pi = pendingIntent(alarm.requestCode, alarm.key, alarm.target, alarm.type, alarm.arrivalAtMillis)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAtMillis, pi)
    }

    fun cancel(key: String) {
        val stored = store.all().firstOrNull { it.key == key } ?: return
        manager.cancel(pendingIntent(stored.requestCode, stored.key, stored.target, stored.type, stored.arrivalAtMillis))
        store.remove(key)
    }

    private fun pendingIntent(requestCode: Int, key: String, target: String, type: String, arrival: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("key", key); putExtra("target", target); putExtra("type", type); putExtra("arrival", arrival)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
