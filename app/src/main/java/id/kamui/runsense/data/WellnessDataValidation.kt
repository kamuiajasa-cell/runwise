package id.kamui.runsense.data

import java.time.LocalDate

internal fun isValidWellnessDate(raw: String, today: LocalDate = LocalDate.now()): Boolean {
    val parsed = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return false
    return !parsed.isAfter(today)
}

internal fun finitePositiveWellnessMetric(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it > 0.0 }
