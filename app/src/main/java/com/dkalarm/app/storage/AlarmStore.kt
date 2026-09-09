package com.dkalarm.app.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredAlarm(
    val key: String,
    val requestCode: Int,
    val triggerAtMillis: Long,
    val arrivalAtMillis: Long,
    val target: String,
    val type: String,
    val sourceHash: String
)

class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("dk_alarm_store", Context.MODE_PRIVATE)

    fun isScreenshotSeen(hash: String): Boolean = prefs.getStringSet("seen_screenshots", emptySet())?.contains(hash) == true

    fun markScreenshotSeen(hash: String) {
        val current = prefs.getStringSet("seen_screenshots", emptySet()).orEmpty().toMutableSet()
        current += hash
        prefs.edit().putStringSet("seen_screenshots", current.toList().takeLast(100).toSet()).apply()
    }

    fun hasAlarm(key: String): Boolean = all().any { it.key == key }

    fun upsert(alarm: StoredAlarm) {
        val list = all().filterNot { it.key == alarm.key } + alarm
        write(list)
    }

    fun remove(key: String) = write(all().filterNot { it.key == key })

    fun all(): List<StoredAlarm> {
        val raw = prefs.getString("alarms", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                StoredAlarm(
                    key = o.getString("key"),
                    requestCode = o.getInt("requestCode"),
                    triggerAtMillis = o.getLong("triggerAtMillis"),
                    arrivalAtMillis = o.getLong("arrivalAtMillis"),
                    target = o.getString("target"),
                    type = o.getString("type"),
                    sourceHash = o.optString("sourceHash")
                )
            }.getOrNull()
        }
    }

    private fun write(list: List<StoredAlarm>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("key", a.key); put("requestCode", a.requestCode); put("triggerAtMillis", a.triggerAtMillis)
                put("arrivalAtMillis", a.arrivalAtMillis); put("target", a.target); put("type", a.type); put("sourceHash", a.sourceHash)
            })
        }
        prefs.edit().putString("alarms", arr.toString()).apply()
    }
}
