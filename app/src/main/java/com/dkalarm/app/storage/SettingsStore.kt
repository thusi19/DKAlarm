package com.dkalarm.app.storage

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("dk_alarm_settings", Context.MODE_PRIVATE)
    var leadSeconds: Int
        get() = prefs.getInt("lead_seconds", 60)
        set(value) { prefs.edit().putInt("lead_seconds", value.coerceIn(5, 3600)).apply() }
}
