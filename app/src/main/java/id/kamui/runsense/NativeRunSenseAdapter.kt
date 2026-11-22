package id.kamui.runsense

import id.kamui.runsense.domain.ActivityRecord
import id.kamui.runsense.domain.AthleteProfile
import id.kamui.runsense.domain.StrengthRecord
import id.kamui.runsense.domain.TrainingEngine
import id.kamui.runsense.domain.WellnessRecord
import id.kamui.runsense.domain.RunWalkSettings
import com.runnow.domain.Act
import com.runnow.domain.ActKind
import com.runnow.domain.AthleteSettings
import com.runnow.domain.Profile
import com.runnow.domain.Settings
import com.runnow.domain.Snapshot
import com.runnow.domain.Wellness
import com.runnow.domain.processData
import java.time.ZoneId

/** Merge local manual records with the original RunSense data model, preserving imported stream summaries. */
fun nativeRunSenseAnalysis(
    activities: List<ActivityRecord>,
    strength: List<StrengthRecord>,
    wellness: List<WellnessRecord>,
    profile: AthleteProfile,
    baseSnapshot: Snapshot? = null,
    settingsOverride: Settings? = null,
    runWalk: RunWalkSettings = RunWalkSettings(),
    hotWeather: Boolean = false,
): com.runnow.domain.ProcessResult {
    val mergedActs = LinkedHashMap<String, Act>()
    baseSnapshot?.acts?.forEach { a -> mergedActs[a.icuId ?: a.lid] = a }
    activities.forEach { a ->
        if (!mergedActs.containsKey(a.id)) {
            mergedActs[a.id] = Act(
                start = a.startedAt,
                kind = when (a.type) { "run" -> ActKind.RUN; "walk" -> ActKind.WALK; else -> ActKind.OTHER },
                min = a.durationMin,
                km = a.distanceKm,
                avgHr = a.averageHr.toDouble(),
                lid = a.id,
                name = a.type,
                load = a.externalLoad,
            )
        }
    }

    val mergedWell = LinkedHashMap<String, Wellness>()
    baseSnapshot?.wellness?.forEach { mergedWell[it.id] = it }
    wellness.forEach { w ->
        val old = mergedWell[w.date]
        mergedWell[w.date] = if (old == null) {
            Wellness(id = w.date, restingHR = w.restingHr.toDouble(), hrv = w.hrv, sleepSecs = w.sleepHours * 3600.0)
        } else old.copy(
            restingHR = if (w.restingHr > 0) w.restingHr.toDouble() else old.restingHR,
            hrv = if (w.hrv > 0) w.hrv else old.hrv,
            sleepSecs = if (w.sleepHours > 0) w.sleepHours * 3600.0 else old.sleepSecs,
        )
    }

    val p = (baseSnapshot?.profile ?: Profile()).copy(
        rest = profile.restingHr.toDouble(),
        max = profile.maxHr.toDouble(),
        age = profile.ageYears,
        restManual = true,
        lthrC = profile.thresholdHr,
        ltpC = profile.thresholdPaceSecPerKm,
    )
    val ltPace = "${profile.thresholdPaceSecPerKm / 60}:${(profile.thresholdPaceSecPerKm % 60).toString().padStart(2, '0')}"
    val settings = (settingsOverride ?: baseSnapshot?.settings ?: Settings()).copy(
        athlete = AthleteSettings(lthr = profile.thresholdHr, ltPace = ltPace),
        heat = if (hotWeather) java.time.LocalDate.now().toString() else "",
    )
    val result = Snapshot(
        profile = p,
        acts = mergedActs.values.sortedByDescending { it.start },
        wellness = mergedWell.values.sortedBy { it.id },
        manualTypes = baseSnapshot?.manualTypes ?: emptyMap(),
        nowMs = System.currentTimeMillis(),
        zone = baseSnapshot?.zone ?: ZoneId.systemDefault(),
        settings = settings,
        streams = baseSnapshot?.streams ?: emptyMap(),
    ).processData()
    val load = TrainingEngine.summarize(activities, strength, wellness, profile)
    val subjectivePainConcern = load.rationale.any { it.contains("nyeri", ignoreCase = true) }
    val subjectiveEffortConcern = load.rationale.any { it.contains("RPE", ignoreCase = true) || it.contains("feel berat", ignoreCase = true) }
    val guarded = when {
        subjectivePainConcern -> result.recommendation.copy(
            k = "bad", status = "rest", title = "Pulihkan kaki", duration = "—",
            hr = "—", pace = "Tanpa target",
            reason = "Check-in 7 hari terakhir menunjukkan nyeri bermakna atau berulang. Jangan mengubah nyeri menjadi sesi latihan. Istirahat dan evaluasi; kembali berlari setelah aktivitas harian serta berjalan tidak memicu nyeri.",
            steps = listOf("Saran" to "Istirahat dari lari dan latihan kaki yang memicu nyeri", "Batas" to "Jika nyeri menetap, memburuk, bengkak, atau mengubah langkah, periksakan ke tenaga kesehatan"),
            tag = "RECOVERY", wl = null,
        )
        subjectiveEffortConcern -> result.recommendation.copy(
            k = "wn", status = "train", title = "Easy respons tubuh", duration = "20–30 menit",
            hr = "Zone 1–2", pace = "Sangat santai; RPE 2–3/10",
            reason = "Check-in menunjukkan beberapa sesi terasa sangat berat. Coach menurunkan sesi menjadi easy singkat; jika pemanasan tetap terasa berat, ganti dengan istirahat.",
            steps = listOf("Pemanasan" to "5 menit jalan", "Inti" to "10–20 menit easy, RPE 2–3/10", "Pendinginan" to "5 menit jalan"),
            tag = "RECOVERY", wl = null,
        )
        load.qualitySessions7d >= 2 -> result.recommendation.copy(
            k = "wn", status = "train", title = "Easy saja", duration = "25–35 menit",
            hr = "Zone 1–2", pace = "Santai dan terkontrol",
            reason = "Sudah ada ${load.qualitySessions7d} sesi quality dalam 7 hari. Coach menahan intensitas tambahan agar ada ruang adaptasi.",
            steps = listOf("Pemanasan" to "5–8 menit jalan dan jog", "Inti" to "15–20 menit easy", "Pendinginan" to "5 menit jalan"),
            tag = "RECOVERY", wl = null,
        )
        load.legStrengthLoad48h >= 3.4 -> result.recommendation.copy(
            k = "wn", status = "train", title = "Easy setelah strength", duration = "20–30 menit",
            hr = "Zone 1–2", pace = "Sangat santai",
            reason = "Paparan strength kaki 48 jam terakhir cukup tinggi. Beri ruang pemulihan otot dan jaringan penunjang sebelum quality berikutnya.",
            steps = listOf("Inti" to "20–30 menit easy atau jalan cepat", "Batas" to "Hentikan bila nyeri atau langkah berubah"),
            tag = "RECOVERY", wl = null,
        )
        load.strengthLoad48h >= 4.0 -> result.recommendation.copy(
            k = "wn", status = "train", title = "Pemulihan setelah strength", duration = "20–30 menit",
            hr = "Zone 1", pace = "Santai",
            reason = "Beban strength keseluruhan 48 jam terakhir cukup besar. Hindari menambah intensitas hari ini.",
            steps = listOf("Inti" to "Jalan santai atau easy singkat", "Batas" to "Jangan mengejar pace"),
            tag = "RECOVERY", wl = null,
        )
        else -> result.recommendation
    }
    val adjusted = if (
        runWalk.enabled && guarded.status == "train" &&
        guarded.title in setOf("Easy Run", "Easy terbatas", "Easy recovery", "Long Run", "Easy taper", "Long dipotong", "Recovery Run")
    ) {
        val durationMin = Regex("\\d+").find(guarded.duration)?.value?.toIntOrNull() ?: 30
        val mainMin = (durationMin - 10).coerceAtLeast(5)
        val cycle = runWalk.runSeconds + runWalk.walkSeconds
        val repeats = ((mainMin * 60.0) / cycle).toInt().coerceAtLeast(1)
        val runLabel = "%d:%02d".format(runWalk.runSeconds / 60, runWalk.runSeconds % 60)
        val walkLabel = "%d:%02d".format(runWalk.walkSeconds / 60, runWalk.walkSeconds % 60)
        guarded.copy(
            steps = listOf("Pemanasan" to "5 menit jalan", "Inti" to "$repeats× ($runLabel lari + $walkLabel jalan)", "Pendinginan" to "5 menit jalan"),
            reason = guarded.reason + " Pola run-walk aktif: $runLabel lari / $walkLabel jalan."
        )
    } else guarded
    val weatherAdjusted = if (hotWeather && adjusted.status == "train") {
        adjusted.copy(reason = adjusted.reason + " Cuaca panas terdeteksi (≥30°C): longgarkan pace sekitar 6% dan gunakan HR serta RPE sebagai patokan utama.")
    } else adjusted
    return result.copy(recommendation = weatherAdjusted)
}
