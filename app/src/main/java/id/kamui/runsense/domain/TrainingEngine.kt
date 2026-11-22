package id.kamui.runsense.domain

import kotlin.math.roundToInt

data class AthleteProfile(val restingHr: Int = 63, val maxHr: Int = 185, val thresholdHr: Int = 160, val thresholdPaceSecPerKm: Int = 390, val heightCm: Int = 0, val weightKg: Double = 0.0, val ageYears: Int = 0)
data class ActivityRecord(val id: String, val startedAt: Long, val type: String, val durationMin: Double, val distanceKm: Double, val averageHr: Int, val externalLoad: Double = 0.0, val quality: Boolean = false, val rpe: Int = 0, val feel: String = "", val pain: Int = 0)
data class StrengthRecord(val id: String, val date: String, val category: String, val durationMin: Int, val level: Int, val completed: Boolean, val exercise: String = "", val sets: Int = 0, val reps: Int = 0, val resistanceKg: Double = 0.0, val doseType: String = inferStrengthDoseType(exercise, sets, reps))

/** Timed/isometric movements use duration; dynamic movements use set × repetition. */
fun isDurationBasedStrengthExercise(exercise: String): Boolean {
    val name = exercise.lowercase(java.util.Locale.ROOT)
    return listOf("plank", "wall sit", "hollow hold", "hold", "isometric", "dead hang").any(name::contains)
}

fun inferStrengthDoseType(exercise: String, sets: Int, reps: Int): String =
    if (isDurationBasedStrengthExercise(exercise) || sets <= 0 || reps <= 0) "duration" else "reps"

data class StrengthSuggestion(
    val level: Int,
    val sets: Int,
    val reps: Int,
    val resistanceKg: Double,
    val note: String,
    val doseType: String = "reps",
    val durationMin: Int = 0
)
data class WellnessRecord(val date: String, val restingHr: Int = 0, val sleepHours: Double = 0.0, val hrv: Double = 0.0)
data class RunWalkSettings(val enabled: Boolean = false, val runSeconds: Int = 225, val walkSeconds: Int = 30)
data class WeatherSnapshot(val temperatureC: Double, val apparentC: Double, val humidityPct: Int, val windKmh: Double, val precipitationMm: Double, val weatherCode: Int, val fetchedAt: Long, val latitude: Double, val longitude: Double)
data class TrainingSummary(val acuteLoad: Double, val chronicLoad: Double, val acwr: Double?, val readiness: Int?, val strengthLoad48h: Double, val legStrengthLoad48h: Double, val qualitySessions7d: Int, val recommendation: String, val rationale: List<String>)

object TrainingEngine {
    private const val DAY = 86_400_000L

    /** Conservative per-exercise progression inferred only from completed log history. */
    fun suggestStrength(exercise: String, history: List<StrengthRecord>): StrengthSuggestion {
        val records = history.filter { it.exercise.equals(exercise, ignoreCase = true) }.sortedBy { it.date }
        val timed = isDurationBasedStrengthExercise(exercise)
        val latest = records.lastOrNull()
            ?: return if (timed) StrengthSuggestion(1, 0, 0, 0.0, "Mulai dari durasi nyaman; utamakan kontrol dan teknik.", "duration", 2)
            else StrengthSuggestion(1, 3, 8, 0.0, "Mulai dari dosis dasar; utamakan teknik yang nyaman.", "reps", 0)
        val level = latest.level.coerceIn(0, 4)
        val resistance = latest.resistanceKg.coerceAtLeast(0.0)
        if (timed) {
            val duration = latest.durationMin.coerceAtLeast(1)
            if (!latest.completed) return StrengthSuggestion((level - 1).coerceAtLeast(0), 0, 0, resistance,
                "Sesi terakhir dilewati: ulangi dengan durasi lebih singkat.", "duration", (duration * 0.8).toInt().coerceAtLeast(1))
            val lastTwo = records.takeLast(2)
            if (lastTwo.size < 2 || lastTwo.any { !it.completed }) return StrengthSuggestion(level, 0, 0, resistance,
                "Pertahankan durasi terakhir. Progresi menunggu dua sesi berturut-turut selesai.", "duration", duration)
            return StrengthSuggestion(level, 0, 0, resistance,
                "Dua sesi terakhir selesai: tambah durasi sedikit, tanpa menaikkan effort sekaligus.", "duration", (duration + 1).coerceAtMost(30))
        }
        val sets = latest.sets.takeIf { it > 0 } ?: 3
        val reps = latest.reps.takeIf { it > 0 } ?: 8
        if (!latest.completed) return StrengthSuggestion((level - 1).coerceAtLeast(0), sets.coerceAtMost(2).coerceAtLeast(1),
            reps.coerceAtMost(8).coerceAtLeast(6), resistance,
            "Sesi terakhir dilewati: ulangi dengan dosis lebih ringan, tanpa mengejar progres.", "reps", 0)
        val lastTwo = records.takeLast(2)
        if (lastTwo.size < 2 || lastTwo.any { !it.completed }) return StrengthSuggestion(level, sets, reps, resistance,
            "Pertahankan dosis terakhir. Progresi menunggu dua sesi berturut-turut selesai.", "reps", 0)
        return if (reps >= 12) StrengthSuggestion((level + 1).coerceAtMost(4), (sets + 1).coerceAtMost(5), 8, resistance,
            "Dua sesi terakhir selesai: naikkan satu tingkat dengan set tambahan dan repetisi lebih rendah.", "reps", 0)
        else StrengthSuggestion(level, sets, (reps + 1).coerceAtMost(12), resistance,
            "Dua sesi terakhir selesai: tambah satu repetisi per set, tanpa menaikkan beban sekaligus.", "reps", 0)
    }

    fun activityLoad(a: ActivityRecord, profile: AthleteProfile): Double {
        if (a.externalLoad > 0) return a.externalLoad
        if (a.type == "walk") return a.durationMin * 0.35
        if (a.type == "strength") return a.durationMin * 0.8
        val range = (profile.maxHr - profile.restingHr).coerceAtLeast(40)
        val intensity = if (a.averageHr > 0) ((a.averageHr - profile.restingHr).toDouble() / range) / 0.88 else 0.72
        val factor = intensity.coerceIn(0.45, 1.15)
        return (a.durationMin / 60.0 * 100.0 * factor * factor).roundToInt().toDouble()
    }

    /** One shared dose formula for readiness, Coach advice, and planned-session review. */
    fun strengthDose(item: StrengthRecord): Double {
        val categoryWeight = when (item.category) {
            "hip" -> 1.0
            "calf" -> 1.2
            "leg" -> 1.25
            "core" -> 0.1
            "drill" -> 0.55
            else -> 0.5
        }
        val effortDose = categoryWeight * (1.0 + item.level.coerceIn(0, 4) * 0.45)
        val primaryVolume = if (item.doseType == "duration") {
            (item.durationMin.coerceAtLeast(0) / 10.0).coerceIn(0.5, 2.0)
        } else {
            if (item.sets > 0 && item.reps > 0) (item.sets.toDouble() * item.reps / 30.0).coerceIn(0.5, 2.0) else 0.5
        }
        val externalLoadMultiplier = 1.0 + item.resistanceKg.coerceIn(0.0, 40.0) / 80.0
        return effortDose * primaryVolume * externalLoadMultiplier
    }

    fun strengthLoad48h(strength: List<StrengthRecord>, now: Long = System.currentTimeMillis()): Pair<Double, Double> {
        var total = 0.0
        var legs = 0.0
        strength.filter { it.completed }.forEach { item ->
            val stamp = parseDate(item.date) ?: return@forEach
            val age = now - stamp
            if (age < -12 * 60 * 60 * 1000L || age > 48 * 60 * 60 * 1000L) return@forEach
            val dose = strengthDose(item)
            total += dose
            if (item.category in setOf("hip", "calf", "leg", "drill")) legs += dose
        }
        return total to legs
    }

    fun summarize(activities: List<ActivityRecord>, strength: List<StrengthRecord>, wellness: List<WellnessRecord>, profile: AthleteProfile, now: Long = System.currentTimeMillis()): TrainingSummary {
        val acute = activities.filter { now - it.startedAt in 0..(7 * DAY) }.sumOf { activityLoad(it, profile) }
        val chronic = activities.filter { now - it.startedAt in (7 * DAY)..(28 * DAY) }.sumOf { activityLoad(it, profile) } / 3.0
        val ratio = if (chronic >= 25.0) acute / chronic else null
        val (strengthTotal, legLoad) = strengthLoad48h(strength, now)
        val hard = activities.count { now - it.startedAt in 0..(7 * DAY) && it.quality && it.type == "run" }
        val reasons = mutableListOf<String>()
        var score = 70; var inputs = 0
        val orderedWellness = wellness.sortedBy { it.date }
        val recent = orderedWellness.takeLast(3)
        val baseline = orderedWellness.dropLast(3).filter { it.restingHr > 30 }.map { it.restingHr }.sorted()
        if (recent.any { it.restingHr > 30 } && baseline.size >= 5) {
            val current = recent.filter { it.restingHr > 30 }.map { it.restingHr }.average()
            val delta = current - baseline[baseline.size / 2]
            score += (-delta * 1.3).toInt().coerceIn(-14, 12); inputs++
            if (delta >= 3) reasons += "RHR naik ${delta.roundToInt()} bpm dari baseline"
        }
        val sleep = recent.lastOrNull { it.sleepHours > 0 }?.sleepHours
        if (sleep != null) { inputs++; if (sleep < 5.5) { score -= 8; reasons += "Tidur kurang dari 5,5 jam" } else if (sleep < 6.5) { score -= 3; reasons += "Durasi tidur masih terbatas" } }
        if (ratio != null) {
            inputs++
            when { ratio > 1.5 -> { score -= 20; reasons += "Beban akut jauh di atas kebiasaan" }; ratio > 1.3 -> { score -= 12; reasons += "Beban akut meningkat tajam" }; ratio > 1.15 -> { score -= 5; reasons += "Beban akut sedikit meningkat" }; ratio in 0.8..1.1 -> score += 5; ratio < 0.6 -> { score -= 6; reasons += "Beban latihan jauh di bawah kebiasaan" } }
        }
        if (legLoad >= 3.4) { score -= 5; reasons += "Paparan strength kaki 48 jam terakhir cukup tinggi" }
        else if (strengthTotal >= 4.0) { score -= 4; reasons += "Beban strength keseluruhan 48 jam terakhir cukup tinggi" }
        val yesterday = activities.filter { now - it.startedAt in DAY..(2 * DAY) }.sumOf { activityLoad(it, profile) }
        if (yesterday > 90) { score -= 10; reasons += "Ada sesi berbeban tinggi kemarin" }
        val hardDays = (0..2).takeWhile { offset ->
            val end = now - offset * DAY
            activities.filter { end - DAY < it.startedAt && it.startedAt <= end }.sumOf { activityLoad(it, profile) } > 70
        }.size
        if (hardDays >= 3) score -= 12 else if (hardDays == 2) score -= 5
        // Check-in subjektif ikut memengaruhi readiness, bukan hanya evaluasi rencana mingguan.
        // Minimal dua respons diperlukan untuk RPE/feel; satu nyeri bermakna tetap menjadi sinyal kehati-hatian.
        val recentCheckins = activities.filter {
            it.type == "run" && now - it.startedAt in 0..(7 * DAY) &&
                (it.rpe > 0 || it.pain > 0 || it.feel.isNotBlank())
        }
        val checked = recentCheckins.filter { it.rpe in 1..10 }
        val averageRpe = checked.map { it.rpe }.average().takeIf { checked.size >= 2 }
        val significantPain = recentCheckins.any { it.pain >= 4 }
        val repeatedPain = recentCheckins.count { it.pain >= 3 } >= 2
        val repeatedHeavyFeel = recentCheckins.count {
            it.feel.equals("sangat berat", true) || it.feel.equals("berat", true)
        } >= 2
        val subjectivePenalty = when {
            significantPain -> 12
            repeatedPain -> 8
            averageRpe != null && averageRpe >= 8.0 -> 6
            repeatedHeavyFeel || (averageRpe != null && averageRpe >= 7.5) -> 3
            else -> 0
        }
        if (subjectivePenalty > 0) {
            score -= subjectivePenalty
            inputs++
            reasons += when {
                significantPain -> "Ada check-in nyeri >=4/10 dalam 7 hari"
                repeatedPain -> "Nyeri >=3/10 berulang pada beberapa sesi"
                averageRpe != null && averageRpe >= 8.0 -> "RPE rata-rata ${averageRpe.roundToInt()}/10 pada minimal dua sesi"
                else -> "RPE/feel berat berulang dalam 7 hari"
            }
        }
        val readiness = if (inputs > 0) score.coerceIn(22, 98) else null
        val recommendation = when {
            significantPain -> "Ada laporan nyeri bermakna. Jangan jalankan sesi quality; pilih istirahat dan evaluasi nyeri sebelum kembali berlatih."
            repeatedPain -> "Nyeri berulang terdeteksi dari check-in. Hentikan sesi yang memicu nyeri dan prioritaskan pemulihan."
            (averageRpe != null && averageRpe >= 8.0) || repeatedHeavyFeel -> "Respons latihan terasa berat berulang. Turunkan intensitas; pilih easy ringan atau pemulihan."
            hard >= 2 -> "Hindari quality tambahan; pilih easy ringan atau pemulihan."
            legLoad >= 3.4 -> "Beri jarak untuk otot kaki setelah strength; pilih easy ringan atau istirahat."
            strengthTotal >= 4.0 -> "Beban strength 48 jam terakhir cukup besar; pilih easy ringan atau pemulihan."
            readiness != null && readiness < 45 -> "Prioritaskan pemulihan. Jangan mengejar target intensitas."
            ratio != null && ratio > 1.3 -> "Tahan kenaikan beban. Sesi berikutnya sebaiknya easy."
            else -> "Ikuti sesi terencana dengan kontrol effort dan evaluasi respons tubuh."
        }
        if (hard >= 2) reasons += "Sudah ada $hard sesi quality dalam 7 hari"
        if (reasons.isEmpty()) reasons += "Belum ada sinyal beban atau pemulihan yang kuat; tetap pantau respons tubuh"
        return TrainingSummary(acute, chronic, ratio, readiness, strengthTotal, legLoad, hard, recommendation, reasons)
    }

    fun cadenceSpm(raw: Double): Double? = when { !raw.isFinite() || raw <= 0 -> null; raw < 120 -> raw * 2.0; else -> raw }
    fun classifyCadenceWindow(cadenceSpm: Double?): String = if (cadenceSpm != null && cadenceSpm > 135.0) "Lari" else "Jalan"

    private fun parseDate(date: String): Long? = try {
        val p = date.split("-").map { it.toInt() }
        java.util.Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2], 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    } catch (_: Exception) { null }

    data class PlanAdvice(val status: String, val detail: String)

    /** Non-destructive advice: the saved weekly plan is never rewritten. */
    fun advisePlannedSession(
        title: String,
        plannedDate: java.time.LocalDate,
        activities: List<ActivityRecord>,
        strength: List<StrengthRecord>,
        summary: TrainingSummary,
    ): PlanAdvice? {
        val normalized = title.lowercase(java.util.Locale.ROOT)
        val quality = listOf("interval", "tempo", "threshold", "quality", "fartlek", "speed", "vo2").any { normalized.contains(it) }
        val longRun = listOf("long", "panjang", "endurance").any { normalized.contains(it) }
        val demanding = quality || longRun

        val recentLegDose = strength.asSequence()
            .filter { it.completed && it.category in setOf("leg", "calf", "hip", "drill") }
            .mapNotNull { item ->
                val date = runCatching { java.time.LocalDate.parse(item.date) }.getOrNull() ?: return@mapNotNull null
                val daysBefore = java.time.temporal.ChronoUnit.DAYS.between(date, plannedDate)
                if (daysBefore !in 1..2) return@mapNotNull null
                strengthDose(item)
            }.sum()

        val recentHardRun = activities.any { activity ->
            if (activity.type != "run" || !activity.quality) return@any false
            val runDate = java.time.Instant.ofEpochMilli(activity.startedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val daysBefore = java.time.temporal.ChronoUnit.DAYS.between(runDate, plannedDate)
            daysBefore in 0..1
        }

        if (demanding && recentLegDose >= 2.0) {
            return PlanAdvice(
                "TINJAU ULANG SESI",
                "Ada beban strength kaki %.1f unit dalam 48 jam sebelumnya. Pertimbangkan mengubah sesi %s menjadi easy ringan atau menggesernya; jangan memaksakan intensitas."
                    .format(java.util.Locale.US, recentLegDose, if (quality) "quality" else "long")
            )
        }
        if (demanding && recentHardRun) {
            return PlanAdvice(
                "JARAK QUALITY BERDEKATAN",
                "Ada sesi quality dalam 48 jam sebelum jadwal ini. Pastikan pemulihan cukup; bila kaki belum pulih, pilih easy atau geser sesi."
            )
        }
        if (demanding && summary.qualitySessions7d >= 2) {
            return PlanAdvice(
                "BATASI INTENSITAS",
                "Sudah ada ${summary.qualitySessions7d} sesi quality dalam 7 hari. Hindari menambah sesi berat; prioritaskan easy atau pemulihan."
            )
        }
        if (demanding && (summary.readiness ?: 70) < 45) {
            return PlanAdvice(
                "PRIORITASKAN PEMULIHAN",
                "Readiness rendah (${summary.readiness}/100). Tunda sesi berat sampai respons tubuh membaik."
            )
        }
        if (demanding && summary.acwr != null && summary.acwr > 1.3) {
            return PlanAdvice(
                "TUNDA KENAIKAN BEBAN",
                "ACWR ${"%.2f".format(java.util.Locale.US, summary.acwr)} menunjukkan beban akut meningkat tajam. Ubah sesi ini menjadi easy."
            )
        }
        if (!demanding && recentLegDose >= 2.0) {
            return PlanAdvice(
                "PANTAU KAKI",
                "Strength kaki 48 jam sebelumnya %.1f unit. Jaga sesi tetap ringan dan hentikan bila nyeri atau langkah berubah."
                    .format(java.util.Locale.US, recentLegDose)
            )
        }
        return null
    }

}
