package com.dkalarm.app.storage

import android.content.Context
import com.dkalarm.app.model.AttackColor
import com.dkalarm.app.model.CrownState
import com.dkalarm.app.model.EditableAttack
import com.dkalarm.app.model.ValidationState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class ReviewStore(context: Context) {
    private val prefs = context.getSharedPreferences("dk_alarm_review", Context.MODE_PRIVATE)

    fun save(attacks: List<EditableAttack>) {
        val array = JSONArray()
        attacks.forEach { attack ->
            array.put(JSONObject().apply {
                put("id", attack.id)
                put("targetVillage", attack.targetVillage)
                put("arrivalText", attack.arrivalText)
                put("arrivalEpochMillis", attack.arrivalInstant?.toEpochMilli() ?: JSONObject.NULL)
                put("color", attack.color.name)
                put("crown", attack.crown.name)
                put("validation", attack.validation.name)
                put("sourceScreenshotHash", attack.sourceScreenshotHash)
                put("warnings", JSONArray(attack.warnings))
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun load(): List<EditableAttack> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val warningsArray = o.optJSONArray("warnings") ?: JSONArray()
                val warnings = buildList {
                    for (j in 0 until warningsArray.length()) add(warningsArray.optString(j))
                }
                val arrival = if (o.isNull("arrivalEpochMillis")) null
                else runCatching { Instant.ofEpochMilli(o.getLong("arrivalEpochMillis")) }.getOrNull()
                add(
                    EditableAttack(
                        id = o.optString("id"),
                        targetVillage = o.optString("targetVillage"),
                        arrivalText = o.optString("arrivalText"),
                        arrivalInstant = arrival,
                        color = enumOrDefault(o.optString("color"), AttackColor.UNKNOWN),
                        crown = enumOrDefault(o.optString("crown"), CrownState.MAYBE),
                        validation = enumOrDefault(o.optString("validation"), ValidationState.CHECK_TIME),
                        warnings = warnings,
                        sourceScreenshotHash = o.optString("sourceScreenshotHash")
                    )
                )
            }
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    companion object {
        private const val KEY = "pending_review"
    }
}
