package id.kamui.runsense.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WellnessDataValidationTest {
    private val today = LocalDate.parse("2026-11-22")

    @Test fun rejectsFutureAndMalformedWellnessDates() {
        assertTrue(isValidWellnessDate("2026-11-22", today))
        assertTrue(isValidWellnessDate("2026-11-21", today))
        assertFalse(isValidWellnessDate("2026-11-23", today))
        assertFalse(isValidWellnessDate("2026-02-30", today))
        assertFalse(isValidWellnessDate("", today))
    }

    @Test fun missingZeroNegativeAndNonFiniteMetricsAreAbsent() {
        assertEquals(null, finitePositiveWellnessMetric(null))
        assertEquals(null, finitePositiveWellnessMetric(0.0))
        assertEquals(null, finitePositiveWellnessMetric(-1.0))
        assertEquals(null, finitePositiveWellnessMetric(Double.NaN))
        assertEquals(null, finitePositiveWellnessMetric(Double.POSITIVE_INFINITY))
        assertEquals(7.5, finitePositiveWellnessMetric(7.5)!!, 0.0)
    }
}
