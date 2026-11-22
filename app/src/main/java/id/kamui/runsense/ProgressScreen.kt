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

@Composable
internal fun ProgressScreen(
    activities: List<ActivityRecord>,
    summary: TrainingSummary,
    domainResult: com.runnow.domain.ProcessResult?,
    snapshot: Snapshot?,
    repository: RunSenseRepository,
    strength: List<StrengthRecord>,
    profile: AthleteProfile,
    modifier: Modifier,
    onCheckin: (ActivityRecord) -> Unit,
) {
    var rangeWeeks by remember { mutableIntStateOf(8) }
    val now = System.currentTimeMillis()
    val today = java.time.LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val fromDate = monday.minusWeeks((rangeWeeks - 1).toLong())
    val zone = java.time.ZoneId.systemDefault()
    val fromMs = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val previousFrom = fromDate.minusWeeks(rangeWeeks.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayMs = 86_400_000.0
    fun median(values: List<Double>): Double? {
        val v = values.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return null
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2.0
    }
    fun paceText(minPerKm: Double): String {
        if (!minPerKm.isFinite() || minPerKm <= 0) return "—"
        val sec = (minPerKm * 60).roundToInt()
        return RunSenseFormat.pace(minPerKm)
    }
    fun confidence(n: Int, span: Double): String = when {
        n >= 12 && span >= 56 -> "tinggi"
        n >= 6 && span >= 28 -> "sedang"
        else -> "rendah"
    }
    val validRuns = activities.filter { a ->
        a.type.equals("run", true) && a.startedAt in fromMs..now &&
            a.durationMin >= 5 && a.distanceKm >= 1 && a.durationMin / a.distanceKm in 2.5..12.0
    }.sortedBy { it.startedAt }
    val previousRuns = activities.filter { a ->
        a.type.equals("run", true) && a.startedAt in previousFrom until fromMs &&
            a.durationMin >= 5 && a.distanceKm >= 1 && a.durationMin / a.distanceKm in 2.5..12.0
    }
    val span = if (validRuns.size > 1) (validRuns.last().startedAt - validRuns.first().startedAt) / dayMs else 0.0
    val historyComplete = activities.minOfOrNull { it.startedAt }?.let { it <= previousFrom + 14 * dayMs.toLong() } ?: false

    // 1. Performa: pace terbaik pada kelompok jarak yang sama.
    val buckets = listOf(Triple("3–5 km", 3.0, 5.0), Triple("5–10 km", 5.0, 10.0), Triple("10–21 km", 10.0, 21.0975), Triple("≥21 km", 21.0975, 1000.0))
    val performanceRows = mutableListOf<Pair<String, String>>()
    val deltas = mutableListOf<Double>()
    buckets.forEach { (label, low, high) ->
        fun best(list: List<ActivityRecord>) = list.filter { it.distanceKm >= low && it.distanceKm < high }
            .minByOrNull { it.durationMin / it.distanceKm }
        val current = best(validRuns) ?: return@forEach
        val prior = best(previousRuns)
        val currentPace = current.durationMin / current.distanceKm
        val priorPace = prior?.let { it.durationMin / it.distanceKm }
        val delta = priorPace?.let { (currentPace - it) * 60.0 }
        if (delta != null) deltas += delta
        val change = delta?.let { " · " + when { it < -1 -> "−${abs(it).roundToInt()} dtk"; it > 1 -> "+${it.roundToInt()} dtk"; else -> "sama" } } ?: ""
        performanceRows += label to "${RunSenseFormat.pace(currentPace)}/km · ${RunSenseFormat.distanceKm(current.distanceKm)}$change"
    }
    val performanceStatus = median(deltas)?.let { if (it <= -5) "Membaik" else if (it >= 5) "Perlu perhatian" else "Stabil" } ?: "Belum cukup data"
    val performanceInsight = when {
        deltas.isEmpty() && !historyComplete -> "Riwayat belum mencakup periode pembanding sebelumnya. Belum bisa menyatakan performa naik atau turun."
        deltas.isEmpty() -> "Belum ada kelompok jarak yang memiliki data pada kedua periode."
        else -> "Pace terbaik berasal dari ringkasan aktivitas per kelompok jarak, bukan uji lomba. Rute, cuaca, elevasi, dan usaha belum dikontrol."
    }

    // 2. Efisiensi aerobik: easy run dengan HR valid, dinormalisasi ke median HR.
    val actsById = snapshot?.acts.orEmpty().associateBy { it.icuId ?: it.lid }
    val stage = remember(snapshot) { snapshot?.stage() }
    val easy = validRuns.mapNotNull { activity ->
        val act = actsById[activity.id] ?: return@mapNotNull null
        val guess = snapshot?.let { it.detectType(act, stage ?: it.stage()).type }
        if (guess !in setOf("easy", "recovery") || activity.averageHr < 70 || activity.averageHr > profile.maxHr + 5) return@mapNotNull null
        Triple(activity, activity.durationMin / activity.distanceKm, activity.averageHr.toDouble())
    }.sortedBy { it.first.startedAt }
    val refHr = median(easy.map { it.third })
    val normalized = if (refHr != null && refHr > 0) easy.map { it.second * it.third / refHr } else emptyList()
    val half = normalized.size / 2
    val earlyPace = median(normalized.take(half))
    val latePace = median(normalized.drop(half))
    val efficiencyChange = if (earlyPace != null && latePace != null && earlyPace > 0) (earlyPace - latePace) / earlyPace * 100 else null
    val efficiencyDeltaValue = efficiencyChange ?: 0.0
    val efficiencyStatus = when {
        normalized.size < 4 -> "Belum cukup data"
        efficiencyDeltaValue > 1.5 -> "Membaik"
        efficiencyDeltaValue < -1.5 -> "Menurun"
        else -> "Stabil"
    }
    val efficiencyRows = buildList {
        if (refHr != null) add("HR acuan" to "${refHr.roundToInt()} bpm")
        if (earlyPace != null && latePace != null) {
            add("Pace setara-HR" to "${paceText(earlyPace)} → ${paceText(latePace)} /km")
            add("Perubahan proxy" to (efficiencyChange?.takeIf { it.isFinite() }?.let { "%+.1f%%".format(Locale.US, it) } ?: "—"))
        }
        add("Easy run valid" to "${easy.size} sesi · minimal 4")
    }

    // 3. Kapasitas latihan: volume per minggu, minggu aktif, CTL jika tersedia.
    val weekly = (0 until rangeWeeks).map { index ->
        val start = fromDate.plusWeeks(index.toLong())
        val end = start.plusWeeks(1)
        val list = validRuns.filter { a ->
            val d = java.time.Instant.ofEpochMilli(a.startedAt).atZone(zone).toLocalDate()
            !d.isBefore(start) && d.isBefore(end)
        }
        Triple(start, list.sumOf { it.distanceKm }, list.size)
    }
    val completedWeeks = weekly.dropLast(1)
    val activeWeeks = completedWeeks.count { it.third > 0 }
    val firstActive = completedWeeks.indexOfFirst { it.third > 0 }.coerceAtLeast(0)
    val evalWeeks = completedWeeks.drop(firstActive)
    val splitWeeks = evalWeeks.size / 2
    val firstVolume = median(evalWeeks.take(splitWeeks).map { it.second })
    val lastVolume = median(evalWeeks.drop(splitWeeks).map { it.second })
    val volumeDelta = if (firstVolume != null && firstVolume > 0 && lastVolume != null) (lastVolume - firstVolume) / firstVolume * 100 else null
    val capacityStatus = when {
        volumeDelta == null || evalWeeks.size < 4 -> "Belum cukup data"
        volumeDelta >= 10 -> "Volume meningkat"
        volumeDelta <= -10 -> "Volume menurun"
        else -> "Stabil"
    }
    val ctl = snapshot?.wellness.orEmpty().filter { w ->
        w.ctl?.let { it > 0 } == true && runCatching { java.time.LocalDate.parse(w.id) }.getOrNull()?.let { !it.isBefore(fromDate) && !it.isAfter(today) } == true
    }
    val capacityRows = buildList {
        add("Jarak periode" to RunSenseFormat.distanceKm(validRuns.sumOf { it.distanceKm }, aggregate = true))
        add("Sesi lari" to validRuns.size.toString())
        add("Minggu berlatih" to "$activeWeeks dari ${completedWeeks.size} minggu selesai")
        add("Jarak mingguan awal → akhir" to if (firstVolume != null && lastVolume != null) "${firstVolume.toInt()} → ${lastVolume.toInt()} km" else "Belum cukup data")
        if (ctl.size >= 7) add("Kebugaran (CTL)" to "${ctl.first().ctl?.let { RunSenseFormat.decimal(it, 1) } ?: "—"} → ${ctl.last().ctl?.let { RunSenseFormat.decimal(it, 1) } ?: "—"}")
        val longestGap = validRuns.zipWithNext().maxOfOrNull { (a, b) -> (b.startedAt - a.startedAt) / dayMs }
        if (longestGap != null) add("Jeda antar lari terlama" to "${longestGap.roundToInt()} hari")
    }

    // 4. Durability: bandingkan sepertiga akhir dan tengah dari stream lari easy/long.
    data class DurSample(val date: Long, val paceDrift: Double, val hrDrift: Double?, val efficiencyDrop: Double?)
    val durabilityIds = remember(validRuns) { validRuns.filter { it.distanceKm >= 5 || it.durationMin >= 40 }.take(60).map { it.id } }
    val durabilityStreams by produceState<Map<String, List<RawStreamPoint>>>(emptyMap(), durabilityIds) {
        value = withContext(Dispatchers.IO) { durabilityIds.associateWith { repository.streamPoints(it) } }
    }
    val durability = validRuns.filter { it.distanceKm >= 5 || it.durationMin >= 40 }.mapNotNull { activity ->
        val act = actsById[activity.id] ?: return@mapNotNull null
        val kind = snapshot?.let { it.detectType(act, stage ?: it.stage()).type }
        if (kind !in setOf("easy", "long")) return@mapNotNull null
        val points = durabilityStreams[activity.id].orEmpty().filter { it.timeSec.isFinite() && it.speedMps > 0.5 }
        if (points.size < 30) return@mapNotNull null
        val start = points.first().timeSec
        val duration = points.last().timeSec - start
        if (duration < 2400) return@mapNotNull null
        fun section(lo: Double, hi: Double) = points.filter {
            val p = (it.timeSec - start) / duration
            p >= lo && p < hi
        }
        val mid = section(1.0 / 3.0, 2.0 / 3.0)
        val late = section(2.0 / 3.0, 1.001)
        if (mid.size < 5 || late.size < 5) return@mapNotNull null
        val midSpeed = mid.map { it.speedMps }.average()
        val lateSpeed = late.map { it.speedMps }.average()
        val midHr = mid.map { it.heartRate }.filter { it > 40 }.takeIf { it.size >= 5 }?.average()
        val lateHr = late.map { it.heartRate }.filter { it > 40 }.takeIf { it.size >= 5 }?.average()
        val midPace = 1000.0 / midSpeed / 60.0
        val latePace = 1000.0 / lateSpeed / 60.0
        val drift = (latePace - midPace) / midPace * 100
        val hrDrift = if (midHr != null && lateHr != null) lateHr - midHr else null
        val midEff = if (midHr != null && midHr > 0) midSpeed / midHr else null
        val lateEff = if (lateHr != null && lateHr > 0) lateSpeed / lateHr else null
        val drop = if (midEff != null && lateEff != null && midEff > 0) (midEff - lateEff) / midEff * 100 else null
        DurSample(activity.startedAt, drift, hrDrift, drop)
    }
    val effDrop = median(durability.mapNotNull { it.efficiencyDrop })
    val durabilityDropValue = effDrop ?: 0.0
    val durabilityStatus = when {
        durability.size < 3 -> "Belum cukup data"
        durabilityDropValue < 3 -> "Stabil"
        durabilityDropValue < 6 -> "Mulai turun"
        else -> "Perlu perhatian"
    }
    val durabilityRows = buildList {
        add("Stream lari panjang valid" to "${durability.size} · minimal 3")
        if (durability.isNotEmpty()) {
            add("Pace drift median" to (median(durability.map { it.paceDrift })?.takeIf { it.isFinite() }?.let { "%+.1f%%".format(Locale.US, it) } ?: "—"))
            median(durability.mapNotNull { it.hrDrift })?.let { add("Perubahan HR median" to "${it.roundToInt()} bpm") }
            if (effDrop != null && effDrop.isFinite()) add("Penurunan efisiensi" to "%.1f%%".format(Locale.US, effDrop))
        }
    }

    // 5. Keberlanjutan: konsistensi, wellness, readiness dan strength.
    val recentStrength = strength.filter { item ->
        item.completed && runCatching { java.time.LocalDate.parse(item.date) }.getOrNull()?.let { !it.isBefore(today.minusDays(27)) } == true
    }
    val previousStrength = strength.filter { item ->
        item.completed && runCatching { java.time.LocalDate.parse(item.date) }.getOrNull()?.let {
            !it.isBefore(today.minusDays(55)) && it.isBefore(today.minusDays(27))
        } == true
    }
    val strengthByCategory = recentStrength.groupBy { it.category }.toSortedMap()
    val strengthSets = recentStrength.sumOf { it.sets }
    val strengthReps = recentStrength.sumOf { it.sets * it.reps }
    val strengthExternalVolume = recentStrength.sumOf { it.sets.toDouble() * it.reps * it.resistanceKg }
    val wellness = snapshot?.wellness.orEmpty()
    val latestWell = wellness.takeLast(7)
    val baselineWell = wellness.dropLast(7)
    val recentRhr = median(latestWell.map { it.restingHR.toDouble() }.filter { it > 30 })
    val baseRhr = median(baselineWell.map { it.restingHR.toDouble() }.filter { it > 30 })
    val sleepMedian = median(latestWell.map { it.sleepSecs / 3600.0 }.filter { it > 0 })
    val readiness = summary.readiness
    val activeRatio = if (completedWeeks.isNotEmpty()) activeWeeks.toDouble() / completedWeeks.size else 0.0
    val maxGap = validRuns.zipWithNext().maxOfOrNull { (a, b) -> (b.startedAt - a.startedAt) / dayMs }
    val sustainabilityStatus = when {
        recentRhr != null && baseRhr != null && recentRhr - baseRhr >= 3 -> "Pantau pemulihan"
        completedWeeks.size < 4 -> "Belum cukup data"
        activeRatio >= .75 -> "Konsisten"
        activeRatio >= .5 -> "Cukup konsisten"
        else -> "Sering terputus"
    }
    val sustainabilityRows = buildList {
        add("Minggu aktif" to "$activeWeeks dari ${completedWeeks.size}")
        add("Jeda antar lari terlama" to (maxGap?.let { "${it.roundToInt()} hari" } ?: "—"))
        add("Strength selesai · 28 hari" to recentStrength.size.toString())
        add("Readiness terbaru" to (readiness?.let { "$it/100" } ?: "Belum ada data"))
        if (recentRhr != null) add("RHR 7 data terbaru" to "${recentRhr.roundToInt()} bpm" + (baseRhr?.let { " · baseline ${it.roundToInt()}" } ?: ""))
        if (sleepMedian != null) add("Tidur median" to "${RunSenseFormat.sleepHours(sleepMedian)}")
    }
    val summaryRows = listOf(
        "Performa" to performanceStatus,
        "Efisiensi aerobik" to efficiencyStatus,
        "Kapasitas latihan" to capacityStatus,
        "Durability" to durabilityStatus,
        "Keberlanjutan" to sustainabilityStatus
    )
    val dateFmt = java.time.format.DateTimeFormatter.ofPattern(if (rangeWeeks >= 26) "d MMM yyyy" else "d MMM", Locale("id", "ID"))

    Page(modifier) {
        PageHero("ANALISIS PERFORMA", "See how far", "you've grown.", "Lima sisi dinilai terpisah, tanpa skor gabungan. Kesimpulan mengikuti kecukupan data.")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(8 to "8 minggu", 12 to "12 minggu", 26 to "6 bulan", 52 to "1 tahun").forEach { (value, label) ->
                FilterChip(selected = rangeWeeks == value, onClick = { rangeWeeks = value }, label = { Text(label, fontSize = 11.sp) })
            }
        }
        Text("PERIODE · ${fromDate.format(dateFmt)} – ${today.format(dateFmt)}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Ringkasan progres", color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                summaryRows.forEach { (label, status) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = White, fontSize = 11.sp)
                        Text(status, color = if (status in setOf("Membaik", "Stabil", "Konsisten")) Lime else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        ProgressDimensionCard("Performa", "Pace terbaik per kelompok jarak dibanding periode sebelumnya", performanceStatus, confidence(deltas.size, span), performanceRows, performanceInsight)
        ProgressDimensionCard("Efisiensi aerobik", "Easy run dengan HR valid · pace dinormalisasi ke HR acuan", efficiencyStatus, confidence(easy.size, if (easy.size > 1) (easy.last().first.startedAt - easy.first().first.startedAt) / dayMs else 0.0), efficiencyRows, if (easy.size < 4) "${easy.size} sesi memenuhi syarat. Dibutuhkan minimal 4 easy run ≥2 km dengan HR valid." else "Ini proxy efisiensi, bukan pengukuran konsumsi oksigen. Suhu, elevasi, tidur, dan hidrasi belum dikontrol.")
        ProgressDimensionCard("Kapasitas latihan", "Volume eksternal, minggu aktif, dan CTL bila tersedia", capacityStatus, confidence(validRuns.size, span), capacityRows, if (volumeDelta == null) "Riwayat minggu selesai belum cukup untuk membandingkan tren volume." else "Median jarak mingguan berubah ${volumeDelta.takeIf { it.isFinite() }?.let { "%+.1f%%".format(Locale.US, it) } ?: "—"}. Minggu kosong tetap dihitung; kenaikan bukan target yang harus dipaksakan.")
        ProgressDimensionCard("Durability", "Kestabilan pace dan HR di sepertiga akhir lari panjang", durabilityStatus, confidence(durability.size, if (durability.size > 1) (durability.last().date - durability.first().date) / dayMs else 0.0), durabilityRows, if (durability.size < 3) "Butuh minimal 3 lari easy/long ≥5 km atau ≥40 menit dengan stream valid. Saat ini ${durability.size} sesi memenuhi syarat." else "Dibandingkan sepertiga tengah dengan sepertiga akhir. Elevasi, suhu, dan fueling belum dikontrol.")
        ProgressDimensionCard("Keberlanjutan", "Konsistensi latihan dan sinyal pemulihan dibanding baseline pribadi", sustainabilityStatus, if (latestWell.size >= 5 || recentStrength.size >= 3) "sedang" else "rendah", sustainabilityRows, if (recentRhr != null && baseRhr != null && recentRhr - baseRhr >= 3) "RHR terbaru ≥3 bpm di atas baseline. Ini sinyal untuk dipantau, bukan diagnosis." else "Data nyeri/cedera tidak tersedia lengkap; kartu ini tidak membuat diagnosis atau skor risiko cedera.")
        SectionTitle("Perkembangan strength", "28 HARI")
        Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Sesi selesai", color = Muted, fontSize = 11.sp)
                        Text(recentStrength.size.toString(), color = Lime, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Total set", color = Muted, fontSize = 11.sp)
                        Text(strengthSets.toString(), color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Repetisi", color = Muted, fontSize = 11.sp)
                        Text(strengthReps.toString(), color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                val change = recentStrength.size - previousStrength.size
                Text(
                    if (previousStrength.isEmpty()) "Pembanding 28 hari sebelumnya belum tersedia."
                    else "Dibanding 28 hari sebelumnya: ${if (change > 0) "+" else ""}$change sesi (${previousStrength.size} → ${recentStrength.size}).",
                    color = Muted, fontSize = 11.sp
                )
                if (strengthExternalVolume > 0) {
                    Text("Volume beban eksternal: ${RunSenseFormat.decimal(strengthExternalVolume, 1)} kg-rep", color = White, fontSize = 11.sp)
                }
            }
        }
        if (strengthByCategory.isEmpty()) {
            EmptyCard("Belum ada strength yang ditandai selesai dalam 28 hari terakhir.")
        } else {
            strengthByCategory.forEach { (category, records) ->
                val sets = records.sumOf { it.sets }
                val reps = records.sumOf { it.sets * it.reps }
                val external = records.sumOf { it.sets.toDouble() * it.reps * it.resistanceKg }
                InfoCard(
                    strengthLabel(category),
                    "${records.size} sesi · $sets set · $reps repetisi" +
                        if (external > 0) " · ${RunSenseFormat.decimal(external, 1)} kg-rep" else "",
                    "28 HARI"
                )
            }
        }
        SectionTitle("Kapasitas mingguan", "$rangeWeeks MINGGU")
        weekly.forEach { (week, km, count) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${week.format(dateFmt)} · $count sesi", color = Muted, fontSize = 11.sp)
                Text(RunSenseFormat.distanceKm(km, aggregate = true), color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = (km / (weekly.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0)).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(5.dp), color = Lime.copy(alpha = .75f), trackColor = Color.White.copy(alpha = .08f))
        }

    }
}

