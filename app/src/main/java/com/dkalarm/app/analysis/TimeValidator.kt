package com.dkalarm.app.analysis

import com.dkalarm.app.model.ValidationState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

data class ParsedTime(
    val arrival: Instant?,
    val absoluteConfidence: Float,
    val relativeConfidence: Float,
    val validation: ValidationState,
    val warnings: List<String>
)

class TimeValidator(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    private val absoluteHms = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d):([0-5]\\d)(?!\\d)")
    // DK "Dorazí za" can be longer than 24 hours, e.g. 40:12:03.
    private val durationHms = Regex("(?<!\\d)(\\d{1,3}):([0-5]\\d):([0-5]\\d)(?!\\d)")
    private val date = Regex("(?<!\\d)([0-3]?\\d)[.\\-/]([01]?\\d)(?:[.\\-/](20\\d{2}))?")

    fun parse(absoluteText: String, relativeText: String, capturedAt: Instant): ParsedTime {
        val warnings = mutableListOf<String>()
        val absMatch = absoluteHms.find(absoluteText)
        val relMatch = durationHms.find(relativeText)
        val relDuration = relMatch?.let {
            val (h, m, s) = it.destructured
            Duration.ofSeconds(h.toLong() * 3600 + m.toLong() * 60 + s.toLong())
        }
        val expected = relDuration?.let { capturedAt.plus(it) }

        var absConfidence = if (absMatch != null) 0.94f else 0f
        val relConfidence = if (relMatch != null) 0.94f else 0f

        val absolute = absMatch?.let {
            val (h, m, s) = it.destructured
            val time = LocalTime.of(h.toInt(), m.toInt(), s.toInt())
            val explicitDate = date.find(absoluteText)?.let { d ->
                val (dd, mm, yyyy) = d.destructured
                val year = if (yyyy.isBlank()) capturedAt.atZone(zoneId).year else yyyy.toInt()
                runCatching { LocalDate.of(year, mm.toInt(), dd.toInt()) }.getOrNull()
            }

            val baseDate = explicitDate
                ?: expected?.atZone(zoneId)?.toLocalDate()
                ?: capturedAt.atZone(zoneId).toLocalDate()

            val candidates = if (explicitDate != null) {
                listOf(LocalDateTime.of(baseDate, time).atZone(zoneId).toInstant())
            } else {
                listOf(baseDate.minusDays(1), baseDate, baseDate.plusDays(1)).map { day ->
                    LocalDateTime.of(day, time).atZone(zoneId).toInstant()
                }
            }

            if (expected != null) {
                candidates.minBy { candidate -> abs(Duration.between(candidate, expected).seconds) }
            } else {
                candidates.filter { !it.isBefore(capturedAt.minusSeconds(120)) }.minOrNull() ?: candidates[1.coerceAtMost(candidates.lastIndex)]
            }
        }

        if (absolute == null) warnings += "Nepodařilo se spolehlivě přečíst absolutní čas Příchod."
        if (relativeText.isNotBlank() && relDuration == null) warnings += "Relativní čas Dorazí za se nepodařilo přečíst."

        var state = if (absolute != null) ValidationState.OK else ValidationState.INVALID
        if (absolute != null && expected != null) {
            val delta = abs(Duration.between(absolute, expected).seconds)
            if (delta > 10) {
                warnings += "Příchod a Dorazí za si odporují o přibližně ${delta} s."
                state = ValidationState.CHECK_TIME
                absConfidence = 0.55f
            }
        } else if (absolute != null && expected == null) {
            warnings += "Čas nebylo možné křížově ověřit pomocí Dorazí za."
            state = ValidationState.CHECK_TIME
            absConfidence = minOf(absConfidence, 0.68f)
        }

        if (absolute != null && absolute.isBefore(capturedAt.minusSeconds(5))) {
            warnings += "Rozpoznaný čas dopadu je v minulosti."
            state = ValidationState.INVALID
        }
        return ParsedTime(absolute, absConfidence, relConfidence, state, warnings)
    }
}
