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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
internal fun TrainingScreen(
    activities: List<ActivityRecord>,
    strength: List<StrengthRecord>,
    profile: AthleteProfile,
    domainResult: com.runnow.domain.ProcessResult?,
    snapshot: Snapshot?,
    repository: RunSenseRepository,
    modifier: Modifier,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onStrength: () -> Unit,
    onCheckin: (ActivityRecord) -> Unit
) {
    var filter by rememberSaveable { mutableStateOf("week") }
    var mode by rememberSaveable { mutableStateOf("run") }
    var selectedActivity by remember { mutableStateOf<ActivityRecord?>(null) }
    var visibleCount by rememberSaveable { mutableIntStateOf(20) }
    val listState = rememberLazyListState()
    val today = java.time.LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    fun inRange(date: java.time.LocalDate): Boolean = when (filter) {
        "week" -> !date.isBefore(monday) && !date.isAfter(today)
        "month" -> date.year == today.year && date.month == today.month && !date.isAfter(today)
        else -> !date.isAfter(today)
    }
    val visibleRuns = remember(activities, filter, today) {
        activities.filter { activity ->
            (activity.type.equals("run", true) || activity.type.equals("walk", true)) &&
                runCatching { java.time.Instant.ofEpochMilli(activity.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull()?.let(::inRange) == true
        }.sortedByDescending { it.startedAt }
    }
    val visibleStrength = remember(strength, filter, today) {
        strength.filter { item -> runCatching { java.time.LocalDate.parse(item.date) }.getOrNull()?.let(::inRange) == true }
            .sortedByDescending { it.date }
    }
    val totalKm = visibleRuns.sumOf { it.distanceKm.takeIf { km -> km.isFinite() && km > 0.0 } ?: 0.0 }
    val totalMinutes = visibleRuns.sumOf { it.durationMin.takeIf { min -> min.isFinite() && min > 0.0 } ?: 0.0 }
    val displayedRuns = visibleRuns.take(visibleCount)
    val groupedRuns = displayedRuns.groupBy { activity ->
        java.time.Instant.ofEpochMilli(activity.startedAt).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().with(java.time.DayOfWeek.MONDAY)
    }.toSortedMap(compareByDescending { it })
    val displayedStrength = visibleStrength.take(visibleCount)

    val pullDistance = remember { mutableFloatStateOf(0f) }
    val refreshConnection = remember(listState, isRefreshing, onRefresh) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f &&
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    pullDistance.floatValue = (pullDistance.floatValue + available.y).coerceAtMost(120f)
                    if (pullDistance.floatValue >= 72f && !isRefreshing) {
                        pullDistance.floatValue = 0f
                        onRefresh()
                    }
                } else if (available.y < 0f) pullDistance.floatValue = 0f
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                pullDistance.floatValue = 0f
                return Velocity.Zero
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(refreshConnection),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isRefreshing) item { SyncBar("Memperbarui riwayat…", true) }
        item { PageHero("YOUR ACTIVITY LOG", "Every run", "has a story.", "Jejak latihan, ritme, dan konsistensimu.") }
        item { RunSenseSegment(mode, listOf("run" to "Lari", "strength" to "Strength"), { mode = it; visibleCount = 20 }) }
        item {
            FilterDock("FILTER") {
                listOf("week" to "Minggu", "month" to "Bulan", "all" to "Semua").forEach { (key, label) ->
                    RunChip(label, filter == key, { filter = key; visibleCount = 20 })
                }
            }
        }
        item {
            val avgPace = if (totalKm > 0.0 && totalMinutes > 0.0) RunSenseFormat.pace(totalMinutes / totalKm) else "—"
            val tiles = if (mode == "run") listOf(
                visibleRuns.size.toString() to "Sesi",
                RunSenseFormat.distanceKm(totalKm, aggregate = true, withUnit = false) to "km",
                RunSenseFormat.durationMinutes(totalMinutes) to "Durasi",
                avgPace to "Pace/km"
            ) else listOf(
                visibleStrength.size.toString() to "Catatan",
                visibleStrength.count { it.completed }.toString() to "Selesai",
                RunSenseFormat.durationWholeMinutes(visibleStrength.sumOf { it.durationMin }) to "Durasi"
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                tiles.forEachIndexed { index, tile ->
                    StatTile(tile.first, tile.second, index == 0, Modifier.weight(1f))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RunButton("＋ Aktivitas", onAdd, Modifier.weight(1f), primary = true)
                RunButton("＋ Strength", onStrength, Modifier.weight(1f))
            }
        }
        if (mode == "run") {
            item { SectionTitle("Riwayat lari & jalan", "${visibleRuns.size} SESI") }
            if (visibleRuns.isEmpty()) item { EmptyCard("Belum ada aktivitas pada periode ini.") }
            groupedRuns.forEach { (weekStart, weekItems) ->
                item(key = "week-$weekStart") {
                    val km = weekItems.sumOf { it.distanceKm.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0 }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 3.dp, end = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (weekStart) {
                                monday -> "Minggu ini"
                                monday.minusWeeks(1) -> "Minggu lalu"
                                else -> "${weekStart.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale("id", "ID")))} – ${weekStart.plusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale("id", "ID")))}"
                            },
                            color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                        Text("${weekItems.size} sesi · ${RunSenseFormat.distanceKm(km, aggregate = true)}", color = Muted, fontSize = 11.sp)
                    }
                }
                items(weekItems, key = { it.id }) { activity ->
                    ActivityCard(activity, profile) { selectedActivity = activity }
                }
            }
            if (visibleCount < visibleRuns.size) item {
                RunButton("Tampilkan lebih banyak", { visibleCount += 20 }, Modifier.fillMaxWidth())
            }
        } else {
            item { SectionTitle("Riwayat strength", "${visibleStrength.size} CATATAN") }
            if (visibleStrength.isEmpty()) item { EmptyCard("Belum ada catatan strength pada periode ini.") }
            items(displayedStrength, key = { it.id }) { item ->
                InfoCard(
                    strengthLabel(item.category),
                    "${RunSenseFormat.strengthDate(item.date)} · ${RunSenseFormat.durationWholeMinutes(item.durationMin)} · level ${item.level}" +
                        (if (item.exercise.isNotBlank()) " · ${item.exercise}: ${item.sets}×${item.reps}" +
                            (if (item.resistanceKg > 0) " @ ${RunSenseFormat.kg(item.resistanceKg)}" else "") else ""),
                    if (item.completed) "SELESAI" else "SKIP"
                )
            }
            if (visibleCount < visibleStrength.size) item {
                RunButton("Tampilkan lebih banyak", { visibleCount += 20 }, Modifier.fillMaxWidth())
            }
        }
    }

    selectedActivity?.let { activity ->
        val domainItem = domainResult?.activities?.firstOrNull {
            it.act.icuId == activity.id || it.act.lid == activity.id || it.act.start == activity.startedAt
        }
        val cadenceValue by produceState("—", activity.id, snapshot) {
            val points = withContext(Dispatchers.IO) {
                domainItem?.let { repository.streamPoints(it.act.icuId ?: it.act.lid) }.orEmpty()
            }
            val result = withContext(Dispatchers.Default) { CadenceAnalyzer.analyze(points, 5) }
            value = result.averageRunningCadence?.toString() ?: "—"
        }
        SessionDetailDialog(
            activity = activity,
            distance = RunSenseFormat.distanceKm(activity.distanceKm, withUnit = false),
            duration = RunSenseFormat.durationMinutes(activity.durationMin),
            pace = if (activity.distanceKm > 0.0) RunSenseFormat.pace(activity.durationMin / activity.distanceKm) else "—",
            averageHr = RunSenseFormat.bpm(activity.averageHr),
            load = RunSenseFormat.integer(TrainingEngine.activityLoad(activity, profile)),
            cadence = cadenceValue,
            onDismiss = { selectedActivity = null },
            onCheckin = { onCheckin(activity); selectedActivity = null },
            analysis = {
                if (activity.type.equals("run", true) && domainItem != null) SessionReviewCard(domainItem, snapshot, repository)
                else if (activity.type.equals("run", true)) EmptyCard("Analisa sesi belum tersedia di data sinkron. Periksa koneksi sinkronisasi dan coba sinkronkan kembali.")
                else EmptyCard("Analisa stream hanya tersedia untuk aktivitas lari.")
            }
        )
    }
}

