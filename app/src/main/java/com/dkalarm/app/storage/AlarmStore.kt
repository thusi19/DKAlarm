package com.dkalarm.app.storage

import android.content.Context
import com.dkalarm.app.core.Rules
import com.dkalarm.app.model.Attack
import com.dkalarm.app.model.StoredAttack
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class AlarmStore(context: Context) {
    // Fresh namespace prevents legacy crown-only alarms from being trusted by this implementation.
    private val prefs = context.getSharedPreferences("dk_text_alarms_v3", Context.MODE_PRIVATE)
    fun all(): List<StoredAttack> = synchronized(LOCK) {
        val arr = JSONArray(prefs.getString("attacks", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = Attack(o.getString("id"), o.getString("villageName"), o.getString("coordinates"),
                Instant.ofEpochSecond(o.getLong("arrivalTime")), o.getBoolean("isNoble"),
                Rules.Noble.valueOf(o.getString("nobleState")), Rules.Color.valueOf(o.getString("attackColor")),
                reviewed = true, rawCommand = o.optString("rawCommand"), sourceHash = o.optString("sourceHash"))
            StoredAttack(a, o.optBoolean("fired"))
        }
    }
    fun merge(attacks: List<Attack>): Int = synchronized(LOCK) {
        val existing = all().associateBy { it.attack.identity }.toMutableMap()
        var added = 0
        attacks.forEach { a ->
            if (!existing.containsKey(a.identity)) { existing[a.identity] = StoredAttack(a); added++ }
        }
        write(existing.values.toList()); added
    }
    fun fired(ids: Set<String>) = synchronized(LOCK) {
        write(all().map { if (it.attack.identity in ids) it.copy(fired = true) else it })
    }
    fun remove(id: String) = synchronized(LOCK) { write(all().filterNot { it.attack.identity == id }) }
    fun clear() = synchronized(LOCK) { write(emptyList()) }
    private fun write(values: List<StoredAttack>) {
        val arr = JSONArray()
        values.forEach { v ->
            val a = v.attack
            arr.put(JSONObject().apply {
                put("id",a.id); put("villageName",a.villageName); put("coordinates",a.coordinates)
                put("arrivalTime",requireNotNull(a.arrivalTime).epochSecond); put("isNoble",a.isNoble)
                put("nobleState",a.nobleState.name); put("attackColor",a.attackColor.name)
                put("rawCommand",a.rawCommand); put("sourceHash",a.sourceHash); put("fired",v.fired)
            })
        }
        check(prefs.edit().putString("attacks",arr.toString()).commit()) { "Alarmy se nepodařilo uložit." }
    }
    companion object { private val LOCK = Any() }
}
