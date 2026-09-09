package com.dkalarm.app.analysis

import com.dkalarm.app.model.ValidationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeValidatorTest {
    private val validator = TimeValidator(ZoneId.of("Europe/Prague"))

    @Test fun matchingAbsoluteAndRelativeAreOk() {
        val captured = Instant.parse("2026-09-09T16:00:00Z") // 18:00 Prague
        val p = validator.parse("18:01:00", "00:01:00", captured)
        assertEquals(ValidationState.OK, p.validation)
        assertNotNull(p.arrival)
    }

    @Test fun conflictingTimesRequireReview() {
        val captured = Instant.parse("2026-09-09T16:00:00Z")
        val p = validator.parse("18:10:00", "00:01:00", captured)
        assertEquals(ValidationState.CHECK_TIME, p.validation)
    }
}
