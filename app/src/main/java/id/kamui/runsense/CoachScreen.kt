package id.kamui.runsense

import android.os.Bundle
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import id.kamui.runsense.data.RunSenseRepository
import id.kamui.runsense.data.NativeIntervalsRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import id.kamui.runsense.domain.*
import com.runnow.domain.ActivityItem
import com.runnow.domain.Snapshot
import com.runnow.domain.reviewRun
import com.runnow.domain.stage
import com.runnow.domain.detectType
import com.runnow.domain.PLAN_DAYS
import com.runnow.domain.hasPlan
import com.runnow.domain.planOf
import com.runnow.domain.ymd
import com.runnow.domain.racePhase
import com.runnow.domain.cutbackDue
import com.runnow.domain.flagInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs

internal fun CoachScreen(activities: List<ActivityRecord>, summary: TrainingSummary, strength: List<StrengthRecord>, strengthTarget: Int, parqAnswers: List<Boolean?>, domainResult: com.runnow.domain.ProcessResult?, snapshot: Snapshot?, planSettings: com.runnow.domain.Settings, weather: WeatherSnapshot?, weatherMessage: String, modifier: Modifier, onRefreshWeather: () -> Unit, onWorkout: () -> Unit) {
    Page(modifier) {
        PageHero("PELATIHAN ADAPTIF", "Train with", "better insight.", "Evaluasi beban, pemulihan, strength, dan kondisi cuaca.")
        DisclosureSection("Kondisi hari ini", "Cuaca · readiness · rekomendasi", initiallyExpanded = true) {
            WeatherCard(weather, weatherMessage, onRefreshWeather)
            val parqYes = parqAnswers.count { it == true }
            val parqAnswered = parqAnswers.count { it != null }
            if (parqYes > 0) {
                Surface(color = Color(0xFF3A2A1B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("PERHATIAN · PAR-Q: ${parqYes} JAWABAN YA", color = Color(0xFFFFC36A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Coach tidak menganggap Anda otomatis mendapat izin untuk berlatih. Konsultasikan jawaban ini dengan tenaga kesehatan sebelum memulai atau meningkatkan latihan, terutama latihan intensitas tinggi.", color = White, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            } else if (parqAnswered < 7) {
                InfoCard("Skrining PAR-Q belum lengkap", "${parqAnswered} dari 7 pertanyaan terisi. Lengkapi di Saya agar Coach dapat menampilkan status skrining.", "BELUM LENGKAP")
            } else {
                InfoCard("Skrining PAR-Q", "Tidak ada jawaban YA pada skrining terakhir. Ini bukan jaminan bebas risiko atau izin medis.", "TERISI")
            }
            ReadinessCard(summary.readiness ?: domainResult?.athlete?.score, summary.recommendation)
            CoachRecommendationCard(domainResult?.recommendation, onWorkout)
        }

        DisclosureSection("Kapasitas & ambang", "Tahap · ambang laktat · estimasi") {
            domainResult?.athlete?.let { athlete ->
                InfoCard("Tahap kapasitas", athlete.stageName, "STAGE ${athlete.stage}")
                InfoCard("Ambang laktat", "LTHR ${athlete.lthr ?: "—"} bpm · LT pace ${athlete.ltPace ?: "—"}", "AUTO / PROFIL")
                InfoCard("Estimasi 5K", domainResult.goalEst.current, domainResult.goalEst.projection)
            }
            MetricCard("ACWR", summary.acwr?.let { RunSenseFormat.decimal(it, 2) } ?: "Belum cukup data beban kronis", Modifier.fillMaxWidth())
        }
        val weekStart = java.time.LocalDate.now().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        val weekEnd = weekStart.plusDays(6)
        val checkins7d = activities.count { item ->
            item.type.equals("run", true) && (item.rpe > 0 || item.pain > 0 || item.feel.isNotBlank()) &&
                java.time.Instant.ofEpochMilli(item.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { !it.isBefore(java.time.LocalDate.now().minusDays(6)) && !it.isAfter(java.time.LocalDate.now()) }
        }
        val strengthDaysThisWeek = strength.filter { it.completed && runCatching { java.time.LocalDate.parse(it.date) }.getOrNull()?.let { date -> !date.isBefore(weekStart) && !date.isAfter(weekEnd) } == true }.map { it.date }.distinct().size
        DisclosureSection("Beban & strength", "Ringkasan beban, check-in, dan strength") {
            InfoCard("Sesi quality 7 hari", "${summary.qualitySessions7d} sesi tercatat", if (summary.qualitySessions7d >= 2) "KUNCI QUALITY" else "TERPANTAU")
            InfoCard("Strength kaki 48 jam", "${RunSenseFormat.decimal(summary.legStrengthLoad48h, 1)} unit beban", if (summary.legStrengthLoad48h >= 3.4) "WASPADA BEBAN" else "NORMAL")
            InfoCard("Total strength 48 jam", "${RunSenseFormat.decimal(summary.strengthLoad48h, 1)} unit beban", if (summary.strengthLoad48h >= 4.0) "BEBAN TINGGI" else "TERPANTAU")
            InfoCard("Respons tubuh 7 hari", "$checkins7d check-in lari tercatat", if (checkins7d > 0) "TERCATAT" else "BELUM ADA")
            InfoCard("Strength minggu ini", "$strengthDaysThisWeek / $strengthTarget hari tercapai", if (strengthDaysThisWeek >= strengthTarget && strengthTarget > 0) "TARGET TERCAPAI" else "PROGRES")
            Text("Check-in dilakukan dari detail sesi. Hanya strength yang ditandai selesai masuk ke beban Coach.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        DisclosureSection("Rencana & alasan", "Target, realisasi, dan dasar keputusan") {
        SectionTitle("Rencana vs realisasi", "MINGGU INI")
        if (planSettings.plan.isEmpty()) {
            EmptyCard("Belum ada rencana mingguan tersimpan di profil RunSense. Setelah rencana tersedia, Coach akan membandingkan target harian dengan kilometer aktual dan strength yang selesai.")
        } else {
            val today = snapshot?.ymd()?.let { java.time.LocalDate.parse(it) } ?: java.time.LocalDate.now()
            val monday = today.minusDays((today.dayOfWeek.value + 6).toLong() % 7L)
            val plannedSessions = PLAN_DAYS.mapNotNull { day ->
                planSettings.plan[day]?.let { p ->
                    PlannedSession(monday.plusDays(if (day == 0) 6 else (day - 1).toLong()), p.t, p.km)
                }
            }
            val weekReview = WeeklyPlanReviewer.evaluate(today, plannedSessions, activities, strength, summary)
            if (planSettings.race.isNotBlank()) {
                val daysToRace = runCatching {
                    java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(planSettings.race)).toInt()
                }.getOrNull()
                val raceTag = when {
                    daysToRace == null -> "TANGGAL"
                    daysToRace < 0 -> "SELESAI"
                    daysToRace == 0 -> "HARI INI"
                    else -> "H-$daysToRace"
                }
                InfoCard("Target race", "${planSettings.raceDist} km · ${planSettings.raceGoal.ifBlank { "waktu belum ditentukan" }} · ${planSettings.race}", raceTag)
            }
            WeeklyReviewCard(weekReview)
            SectionTitle("Usulan jadwal minggu depan", "ADAPTIF")
            weekReview.nextWeekSessions.forEach { suggested ->
                val dayName = suggested.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("id", "ID"))
                val km = if (suggested.targetKm > 0) " · ${RunSenseFormat.distanceKm(suggested.targetKm, aggregate = true)}" else ""
                InfoCard("$dayName · ${suggested.date.dayOfMonth}/${suggested.date.monthValue}", suggested.title + km,
                    if (suggested.title.startsWith("Easy ringan")) "DIUBAH COACH" else "DIPERTAHANKAN")
            }
            SectionTitle("Rincian per hari", "TARGET · AKTUAL")
            weekReview.days.forEach { day ->
                val dayName = day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("id", "ID"))
                val target = if (day.targetKm > 0) "target ${RunSenseFormat.distanceKm(day.targetKm, aggregate = true)}" else "target sesi"
                val actual = if (day.actualKm > 0) "aktual ${RunSenseFormat.distanceKm(day.actualKm, aggregate = true)}" else "belum ada lari"
                val dayStrength = strength.filter { it.date == day.date.toString() && it.completed }
                val extra = if (dayStrength.isNotEmpty()) " · strength: " + dayStrength.joinToString { strengthLabel(it.category) } else ""
                InfoCard("$dayName · ${day.date.dayOfMonth}/${day.date.monthValue}", "${day.title} · $target · $actual$extra", day.status)
                if (!day.date.isBefore(today)) {
                    val advice = TrainingEngine.advisePlannedSession(day.title, day.date, activities, strength, summary)
                    if (advice != null) InfoCard("Catatan Coach · $dayName", advice.detail, advice.status)
                }
            }
            SectionTitle("Strength per hari", "RENCANA · AKTUAL")
            (0L..6L).forEach { offset ->
                val date = monday.plusDays(offset)
                val planKey = if (offset == 6L) 0 else (offset + 1).toInt()
                val plannedTitle = planSettings.plan[planKey]?.t.orEmpty()
                val isStrengthPlanned = listOf("strength", "gym", "kekuatan", "beban").any { plannedTitle.contains(it, ignoreCase = true) }
                val dayRecords = strength.filter { it.date == date.toString() }
                val completedRecords = dayRecords.filter { it.completed }
                if (isStrengthPlanned || dayRecords.isNotEmpty()) {
                    val dayName = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("id", "ID"))
                    val plannedText = if (isStrengthPlanned) "Target: $plannedTitle" else "Strength tambahan"
                    val actualText = when {
                        completedRecords.isNotEmpty() -> completedRecords.joinToString { record ->
                            strengthLabel(record.category) + if (record.exercise.isNotBlank()) " · ${record.exercise} ${record.sets}×${record.reps}" else ""
                        }
                        dayRecords.isNotEmpty() -> "Ada catatan, tetapi belum ditandai selesai"
                        else -> "Belum ada catatan strength"
                    }
                    InfoCard("$dayName · ${date.dayOfMonth}/${date.monthValue}", "$plannedText · Aktual: $actualText",
                        if (completedRecords.isNotEmpty()) "TERLAKSANA" else if (isStrengthPlanned) "BELUM TERCATAT" else "BELUM SELESAI")
                }
            }
            Text("Coach membaca sesi lari yang tersimpan dan strength yang ditandai selesai. Kilometer yang terlewat tidak dipindahkan otomatis; saran minggu depan mempertimbangkan konsistensi, ACWR, readiness, dan beban kaki.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        SectionTitle("Alasan rekomendasi", "TRANSPARAN")
        summary.rationale.forEachIndexed { index, reason -> InfoCard("Sinyal ${index + 1}", reason, "DATA") }
        Text("Readiness adalah heuristik latihan, bukan diagnosis medis atau prediksi cedera. Nilai dapat belum tersedia jika data pemulihan dan beban belum cukup.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
