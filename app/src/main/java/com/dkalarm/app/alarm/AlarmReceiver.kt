package com.dkalarm.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AlarmSoundService::class.java).apply {
            putExtras(intent)
        }
        context.startForegroundService(service)
    }
}
