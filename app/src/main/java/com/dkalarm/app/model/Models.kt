package com.dkalarm.app.model

import com.dkalarm.app.core.Rules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val GAME_ZONE: ZoneId = ZoneId.of("Europe/Prague")
val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(GAME_ZONE)
val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(GAME_ZONE)

data class Attack(
    val id: String,
    val villageName: String,
    val coordinates: String,
    val arrivalTime: Instant?,
    val isNoble: Boolean,
    val nobleState: Rules.Noble,
    val attackColor: Rules.Color,
    val warning: String = "",
    val reviewed: Boolean = false,
    val selected: Boolean = true,
    val rawCommand: String = "",
    val rawArrival: String = "",
    val sourceHash: String = "",
    val rowTop: Int = 0,
    val rowBottom: Int = 0
) {
    val label: String get() = Rules.label(isNoble, attackColor)
    val important: Boolean get() = Rules.important(isNoble, attackColor)
    val villageKey: String get() = Rules.villageKey(villageName, coordinates)
    val alarmTime: Instant? get() = arrivalTime?.minusSeconds(60)
    val identity: String get() = "$villageKey|${arrivalTime?.epochSecond}|$isNoble|${attackColor.name}"
}

data class StoredAttack(val attack: Attack, val fired: Boolean = false)
data class AlarmSlot(val key: String, val trigger: Instant, val attacks: List<Attack>) {
    val noble: Boolean get() = attacks.any { it.isNoble }
    val title: String get() = attacks.map { "${it.label} – ${it.villageName}" }.distinct().joinToString("; ")
    val arrival: String get() = attacks.first().arrivalTime?.let(TIME_FORMAT::format).orEmpty()
}
