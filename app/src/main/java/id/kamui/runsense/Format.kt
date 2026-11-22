package id.kamui.runsense

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Satu sumber format angka untuk seluruh antarmuka RunSense.
 * Nilai kosong, nol yang bermakna "belum ada data", NaN, dan Infinity tidak pernah ditampilkan mentah.
 */
object RunSenseFormat {
    private val idLocale = Locale("id", "ID")

    fun durationMinutes(value: Double?): String {
        val minutes = value?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        val totalSeconds = (minutes * 60.0).roundToInt().coerceAtLeast(1)
        if (totalSeconds < 3600) return "${(totalSeconds / 60.0).roundToInt()} mnt"
        return "%d:%02d:%02d".format(Locale.US, totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    }

    fun durationWholeMinutes(value: Int?): String =
        value?.takeIf { it > 0 }?.let { "$it mnt" } ?: "—"

    fun distanceKm(value: Double?, aggregate: Boolean = false, withUnit: Boolean = true): String {
        val km = value?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        val digits = if (aggregate) 1 else 2
        val number = "%.${digits}f".format(Locale.US, km)
        return if (withUnit) "$number km" else number
    }

    /** Input dan output pace sama-sama dalam menit/km; output m:ss tanpa unit agar label dapat memuat /km. */
    fun pace(valueMinutesPerKm: Double?): String {
        val pace = valueMinutesPerKm?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        val seconds = (pace * 60.0).roundToInt()
        return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    fun bpm(value: Number?): String {
        val n = value?.toDouble()?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        return n.roundToInt().toString()
    }

    fun integer(value: Double?): String {
        val n = value?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        return n.roundToInt().toString()
    }

    fun decimal(value: Double?, digits: Int = 1, zeroIsMissing: Boolean = true): String {
        val n = value?.takeIf { it.isFinite() && (!zeroIsMissing || it > 0.0) } ?: return "—"
        return "%.${digits}f".format(Locale.US, n)
    }

    fun sleepHours(value: Double?): String {
        val hours = value?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        val totalMinutes = (hours * 60.0).roundToInt()
        return "${totalMinutes / 60}j ${totalMinutes % 60}m"
    }

    fun kg(value: Double?): String {
        val kg = value?.takeIf { it.isFinite() && it > 0.0 } ?: return "—"
        val rounded = "%.${1}f".format(Locale.US, kg)
        val compact = if (rounded.endsWith(".0")) rounded.dropLast(2) else rounded
        return "$compact kg"
    }

    fun strengthDate(isoDate: String): String {
        val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
        return date.format(DateTimeFormatter.ofPattern("d MMM", idLocale))
    }

    fun activityType(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "run", "running" -> "Lari"
        "walk", "walking" -> "Jalan"
        "strength", "gym", "weight" -> "Strength"
        "other", "activity" -> "Aktivitas"
        else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(idLocale) else it.toString() }
    }

    fun valid(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0.0 }
}
