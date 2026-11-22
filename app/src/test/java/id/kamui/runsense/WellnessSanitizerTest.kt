package id.kamui.runsense

import id.kamui.runsense.data.positiveFiniteOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WellnessSanitizerTest {
    @Test
    fun acceptsOnlyPositiveFiniteMeasurements() {
        assertEquals(7.5, positiveFiniteOrNull(7.5)!!, 0.0)
        assertNull(positiveFiniteOrNull(0.0))
        assertNull(positiveFiniteOrNull(-1.0))
        assertNull(positiveFiniteOrNull(Double.NaN))
        assertNull(positiveFiniteOrNull(Double.POSITIVE_INFINITY))
        assertNull(positiveFiniteOrNull(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun convertsValidSleepSecondsToHoursWithoutAllowingNonFiniteValues() {
        assertEquals(8.0, positiveFiniteOrNull(28_800.0 / 3600.0)!!, 0.0)
        assertNull(positiveFiniteOrNull(Double.NaN / 3600.0))
        assertNull(positiveFiniteOrNull(Double.POSITIVE_INFINITY / 3600.0))
        assertNull(positiveFiniteOrNull(0.0 / 3600.0))
    }
}
