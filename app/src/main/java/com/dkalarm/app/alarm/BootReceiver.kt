package com.dkalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dkalarm.app.storage.AlarmStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            val scheduler = AlarmScheduler(context)
            AlarmStore(context).all().forEach { scheduler.rescheduleStored(it) }
        } finally {
            pending.finish()
        }
    }
}
