package id.kamui.runsense

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun durationRejectsMissingAndInvalidValues() {
        assertEquals("—", RunSenseFormat.durationMinutes(null))
        assertEquals("—", RunSenseFormat.durationMinutes(0.0))
        assertEquals("—", RunSenseFormat.durationMinutes(-1.0))
        assertEquals("—", RunSenseFormat.durationMinutes(Double.NaN))
        assertEquals("—", RunSenseFormat.durationMinutes(Double.POSITIVE_INFINITY))
        assertEquals("30 mnt", RunSenseFormat.durationMinutes(30.0))
    }

    @Test fun distanceRejectsMissingAndInvalidValues() {
        assertEquals("—", RunSenseFormat.distanceKm(0.0))
        assertEquals("—", RunSenseFormat.distanceKm(-1.0))
        assertEquals("—", RunSenseFormat.distanceKm(Double.NaN))
        assertEquals("—", RunSenseFormat.distanceKm(Double.POSITIVE_INFINITY))
        assertEquals("5.00 km", RunSenseFormat.distanceKm(5.0))
    }

    @Test fun paceRejectsMissingAndInvalidValues() {
        assertEquals("—", RunSenseFormat.pace(0.0))
        assertEquals("—", RunSenseFormat.pace(-1.0))
        assertEquals("—", RunSenseFormat.pace(Double.NaN))
        assertEquals("—", RunSenseFormat.pace(Double.POSITIVE_INFINITY))
        assertEquals("6:30", RunSenseFormat.pace(6.5))
    }

    @Test fun sleepRejectsMissingAndInvalidValues() {
        assertEquals("—", RunSenseFormat.sleepHours(null))
        assertEquals("—", RunSenseFormat.sleepHours(0.0))
        assertEquals("—", RunSenseFormat.sleepHours(-2.0))
        assertEquals("—", RunSenseFormat.sleepHours(Double.NaN))
        assertEquals("—", RunSenseFormat.sleepHours(Double.POSITIVE_INFINITY))
        assertEquals("7j 30m", RunSenseFormat.sleepHours(7.5))
    }
}