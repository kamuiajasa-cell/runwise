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

private var Ink by mutableStateOf(Color(0xFF0A0A0A))
private var Panel by mutableStateOf(Color(0xFF1E1E1E))
private var Lime by mutableStateOf(Color(0xFFC6FF38))
private var Muted by mutableStateOf(Color(0xFF9E9E9E))
private var White by mutableStateOf(Color(0xFFFAFAFA))

private fun applyRunSenseTheme(light: Boolean) {
    Ink = if (light) Color(0xFFF1F3ED) else Color(0xFF0A0A0A)
    Panel = if (light) Color(0xFFFFFFFF) else Color(0xFF1E1E1E)
    Lime = if (light) Color(0xFF4E8D12) else Color(0xFFC6FF38)
    Muted = if (light) Color(0xFF62695D) else Color(0xFF9E9E9E)
    White = if (light) Color(0xFF171B14) else Color(0xFFFAFAFA)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 10, 10)
        window.navigationBarColor = android.graphics.Color.rgb(10, 10, 10)
        val repository = RunSenseRepository(applicationContext)
        val intervals = NativeIntervalsRuntime(applicationContext)
        setContent { RunSenseNativeApp(repository, intervals) }
    }
}

private enum class RunTab(val label: String, val mark: String) {
    HOME("Beranda", "⌂"), TRAINING("Riwayat", "◷"), PROGRESS("Progress", "↗"),
    PROFILE("Saya", "◎"), COACH("Coach", "✦"), WATCH("Jam", "⌚")
}

private val primaryTabs = listOf(RunTab.HOME, RunTab.TRAINING, RunTab.PROGRESS, RunTab.PROFILE)

@Composable
private fun RunSenseNativeApp(repository: RunSenseRepository, intervals: NativeIntervalsRuntime) {
    var selected by remember { mutableStateOf(RunTab.HOME) }
    var lightTheme by remember { mutableStateOf(repository.isLightTheme()) }
    var onboarding by remember { mutableStateOf(!repository.onboardingComplete() && !intervals.hasApiKey() && repository.activities().isEmpty() && repository.wellness().isEmpty()) }
    var revision by remember { mutableIntStateOf(0) }
    var showActivity by remember { mutableStateOf(false) }
    var showStrength by remember { mutableStateOf(false) }
    var showStrengthTarget by remember { mutableStateOf(false) }
    var showParq by remember { mutableStateOf(false) }
    var showWellness by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var showPlanRace by remember { mutableStateOf(false) }
    var showWorkoutComposer by remember { mutableStateOf(false) }
    var checkinActivity by remember { mutableStateOf<ActivityRecord?>(null) }
    var syncBusy by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val weather = remember(revision) { repository.currentWeather() }
    var weatherMessage by remember { mutableStateOf("") }
    val weatherPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            scope.launch {
                weatherMessage = "Memuat cuaca dari lokasi perangkat…"
                try {
                    withContext(Dispatchers.IO) {
                        val current = repository.fetchCurrentWeather()
                        runCatching { intervals.autoHeatToday(); intervals.refreshClimateContext() }
                        current
                    }
                    weatherMessage = "Cuaca diperbarui dari Open-Meteo."
                    revision++
                } catch (e: Exception) {
                    weatherMessage = e.message ?: "Cuaca belum tersedia."
                }
            }
        } else weatherMessage = "Izin lokasi ditolak. Cuaca belum dapat diperbarui."
    }
    fun refreshWeather() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch {
                weatherMessage = "Memuat cuaca dari lokasi perangkat…"
                try {
                    withContext(Dispatchers.IO) { repository.fetchCurrentWeather() }
                    weatherMessage = "Cuaca diperbarui dari Open-Meteo."
                    revision++
                } catch (e: Exception) {
                    weatherMessage = e.message ?: "Cuaca belum tersedia."
                }
            }
        } else weatherPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    LaunchedEffect(Unit) {
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (permitted && (weather == null || System.currentTimeMillis() - weather.fetchedAt > 6 * 60 * 60 * 1000L)) refreshWeather()
    }
    val profile = remember(revision) { repository.profile() }
    val runWalk = remember(revision) { repository.runWalkSettings() }
    val planSettings = remember(revision) { repository.planSettings() }
    val activities = remember(revision) { repository.activities() }
    val strength = remember(revision) { repository.strength() }
    val wellness = remember(revision) { repository.wellness() }
    val strengthTarget = remember(revision) { repository.weeklyStrengthTarget() }
    val parqAnswers = remember(revision) { repository.parqAnswers() }
    val summary = remember(revision) { TrainingEngine.summarize(activities, strength, wellness, profile) }
    val baseSnapshot = remember(revision) { runCatching { intervals.snapshot() }.getOrNull() }
    val domainResult = remember(revision) { runCatching { nativeRunSenseAnalysis(activities, strength, wellness, profile, baseSnapshot, planSettings, runWalk, (weather?.temperatureC ?: 0.0) >= 30.0) }.getOrNull() }
    fun changed() { revision++ }
    SideEffect {
        applyRunSenseTheme(lightTheme)
        (context as? Activity)?.window?.let { window ->
            val bar = if (lightTheme) android.graphics.Color.rgb(241, 243, 237) else android.graphics.Color.rgb(10, 10, 10)
            window.statusBarColor = bar
            window.navigationBarColor = bar
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = lightTheme
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = lightTheme
        }
    }

    MaterialTheme(colorScheme = if (lightTheme) lightColorScheme(primary = Lime, background = Ink, surface = Panel, onPrimary = Color.White, onBackground = White, onSurface = White) else darkColorScheme(primary = Lime, background = Ink, surface = Panel, onPrimary = Ink, onBackground = White, onSurface = White)) {
        if (onboarding) {
            OnboardingScreen(status = syncMessage, onConnect = { showApiKey = true }, onSkip = { repository.saveOnboardingComplete(); onboarding = false; selected = RunTab.HOME })
        } else Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Ink, contentColor = White, tonalElevation = 0.dp) {
                    primaryTabs.forEach { tab ->
                        NavigationBarItem(selected = selected == tab, onClick = { selected = tab },
                            icon = { Text(tab.mark, fontSize = 17.sp) }, label = { Text(tab.label, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime,
                                indicatorColor = Lime.copy(alpha = .13f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        ) { padding ->
            when (selected) {
                RunTab.HOME -> HomeScreen(activities, summary, domainResult, baseSnapshot, planSettings, weather, weatherMessage, Modifier.padding(padding), onAdd = { showActivity = true }, onStrength = { showStrength = true }, onRefreshWeather = { refreshWeather() }, onWorkout = { showWorkoutComposer = true }, onViewAll = { selected = RunTab.TRAINING })
                RunTab.TRAINING -> TrainingScreen(activities, strength, profile, domainResult, baseSnapshot, repository, Modifier.padding(padding), onAdd = { showActivity = true }, onStrength = { showStrength = true }, onCheckin = { checkinActivity = it })
                RunTab.PROGRESS -> ProgressScreen(
                    activities, summary, domainResult, baseSnapshot, repository, strength, profile, Modifier.padding(padding),
                    onCheckin = { checkinActivity = it }
                )
                RunTab.COACH -> CoachScreen(activities, summary, strength, strengthTarget, parqAnswers, domainResult, baseSnapshot, planSettings, weather, weatherMessage, Modifier.padding(padding), onRefreshWeather = { refreshWeather() }, onWorkout = { showWorkoutComposer = true })
                RunTab.WATCH -> WatchScreen(Modifier.padding(padding)) { json -> repository.importWatchJson(json); intervals.ingestWatchJson(json); changed() }
                RunTab.PROFILE -> SettingsScreen(
                    profile, wellness, Modifier.padding(padding),
                    onCoach = { selected = RunTab.COACH }, onWatch = { selected = RunTab.WATCH },
                    runWalk = runWalk, lightTheme = lightTheme, coachingStyle = planSettings.style,
                    onRunWalk = { repository.saveRunWalkSettings(it); changed() },
                    onTheme = { lightTheme = it; repository.saveLightTheme(it) },
                    onStyle = { style -> repository.savePlanSettings(planSettings.copy(style = style)); changed() },
                    hasIntervalsKey = intervals.hasApiKey(), syncBusy = syncBusy, syncMessage = syncMessage,
                    onProfile = { showProfile = true }, onWellness = { showWellness = true }, onPlanRace = { showPlanRace = true },
                    strengthTarget = strengthTarget, parqAnswers = parqAnswers,
                    onStrengthTarget = { showStrengthTarget = true }, onParq = { showParq = true },
                    onApiKey = { showApiKey = true },
                    onSync = {
                        scope.launch {
                            syncBusy = true
                            syncMessage = "Menyinkronkan Intervals.icu…"
                            try {
                                syncMessage = withContext(Dispatchers.IO) { intervals.sync() }
                                changed()
                            } catch (e: Exception) {
                                syncMessage = "Gagal sinkron: ${e.message}"
                            } finally { syncBusy = false }
                        }
                    }
                )
            }
        }
        checkinActivity?.let { activity ->
            SessionCheckinDialog(activity, onDismiss = { checkinActivity = null }, onSave = { rpe, feel, pain ->
                repository.updateActivityCheckin(activity.id, rpe, feel, pain)
                checkinActivity = null
                changed()
            })
        }
        if (showWorkoutComposer) {
            val recommendation = domainResult?.recommendation
            WorkoutComposerDialog(
                recommendation = recommendation,
                onDismiss = { showWorkoutComposer = false },
                onSend = { name, date, description ->
                    scope.launch {
                        syncBusy = true
                        try {
                            syncMessage = withContext(Dispatchers.IO) {
                                intervals.createPlannedWorkout(name, date, description)
                            }
                            showWorkoutComposer = false
                        } catch (e: Exception) {
                            syncMessage = "Gagal mengirim workout: ${e.message}"
                        } finally { syncBusy = false }
                    }
                }
            )
        }
        if (showActivity) ActivityDialog(onDismiss = { showActivity = false }, onSave = { type, duration, distance, hr, load, quality, rpe, feel, pain ->
            repository.addActivity(type, duration, distance, hr, load, quality, rpe, feel, pain); showActivity = false; changed()
        })
        if (showStrength) StrengthDialog(onDismiss = { showStrength = false }, onSave = { category, duration, level, done, exercise, sets, reps, load ->
            repository.addStrength(category, duration, level, done, exercise, sets, reps, load); showStrength = false; changed()
        })
        if (showStrengthTarget) StrengthTargetDialog(strengthTarget, onDismiss = { showStrengthTarget = false }, onSave = { days -> repository.saveWeeklyStrengthTarget(days); showStrengthTarget = false; changed() })
        if (showParq) ParqDialog(parqAnswers, onDismiss = { showParq = false }, onSave = { answers -> repository.saveParqAnswers(answers); showParq = false; changed() })
        if (showWellness) WellnessDialog(onDismiss = { showWellness = false }, onSave = { rhr, sleep, hrv ->
            repository.addWellness(rhr, sleep, hrv); showWellness = false; changed()
        })
        if (showProfile) ProfileDialog(profile, onDismiss = { showProfile = false }, onSave = {
            repository.saveProfile(it); showProfile = false; changed()
        })
        if (showPlanRace) PlanRaceDialog(planSettings, onDismiss = { showPlanRace = false }, onSave = { settings ->
            repository.savePlanSettings(settings); showPlanRace = false; changed()
        })
        if (showApiKey) ApiKeyDialog(onDismiss = { showApiKey = false }, onSave = { key ->
            showApiKey = false
            scope.launch {
                syncBusy = true
                syncMessage = "Menyinkronkan Intervals.icu…"
                try {
                    syncMessage = withContext(Dispatchers.IO) { intervals.sync(key) }
                    repository.saveOnboardingComplete()
                    onboarding = false
                    changed()
                } catch (e: Exception) {
                    syncMessage = "Gagal sinkron: ${e.message}"
                } finally { syncBusy = false }
            }
        })
    }
}

@Composable
private fun OnboardingScreen(status: String, onConnect: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("RUNSENSE", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(28.dp))
        Text("Build your", color = White, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
        Text("stronger self.", color = Lime, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Text("Hubungkan data latihanmu. RunSense mengubah aktivitas menjadi arahan yang bisa kamu jalani.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(28.dp))
        Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mulai dengan Intervals.icu", color = White, fontWeight = FontWeight.Bold)
                Text("API key disimpan di penyimpanan privat perangkat. Kamu juga bisa melanjutkan tanpa sinkronisasi dan mengimpor data jam nanti.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("Hubungkan & Mulai") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Lanjut tanpa sinkron") }
                if (status.isNotBlank()) Text(status, color = Color(0xFFFFC36A), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun RunWalkSettingsCard(value: RunWalkSettings, onSave: (RunWalkSettings) -> Unit) {
    var enabled by remember(value) { mutableStateOf(value.enabled) }
    var run by remember(value) { mutableStateOf("%d:%02d".format(value.runSeconds / 60, value.runSeconds % 60)) }
    var walk by remember(value) { mutableStateOf("%d:%02d".format(value.walkSeconds / 60, value.walkSeconds % 60)) }
    fun parseTime(raw: String, fallback: Int): Int {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return fallback
        val minutes = parts[0].toIntOrNull() ?: return fallback
        val seconds = parts[1].toIntOrNull() ?: return fallback
        val total = minutes * 60 + seconds
        return if (seconds < 60 && total in 10..3600) total else fallback
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pakai run-walk di semua tahap", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Mengubah struktur sesi Coach untuk sesi yang sesuai.", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; onSave(value.copy(enabled = it)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(run, { run = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Durasi lari (mm:ss)") })
            OutlinedTextField(walk, { walk = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Durasi jalan (mm:ss)") })
        }
        Button(onClick = { onSave(RunWalkSettings(enabled, parseTime(run, value.runSeconds), parseTime(walk, value.walkSeconds))) }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Lime)) { Text("Simpan pola run-walk") }
        Text("Default RunSense: 3:45 lari + 0:30 jalan. Struktur ini hanya diterapkan pada sesi Coach yang sesuai; target pace dan data aktivitas tidak diubah.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun Page(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun HomeScreen(activities: List<ActivityRecord>, summary: TrainingSummary, domainResult: com.runnow.domain.ProcessResult?, snapshot: Snapshot?, planSettings: com.runnow.domain.Settings, weather: WeatherSnapshot?, weatherMessage: String, modifier: Modifier, onAdd: () -> Unit, onStrength: () -> Unit, onRefreshWeather: () -> Unit, onWorkout: () -> Unit, onViewAll: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { HomePageHero() }
        item { HomeWeatherSummary(weather, onRefreshWeather) }
        item { CoachRecommendationCard(domainResult?.recommendation, onWorkout) }
        item { ReadinessCard(summary.readiness ?: domainResult?.athlete?.score, summary.recommendation) }
        item { WeeklyPlanHomeCard(snapshot, planSettings) }
        item { SectionKicker("LATIHANMU", "01") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("BEBAN 7 HARI", fmt(summary.acuteLoad), Modifier.weight(1f))
            MetricCard("ACWR", summary.acwr?.let { "%.2f".format(Locale.US, it) } ?: "—", Modifier.weight(1f))
            MetricCard("STRENGTH 48J", RunSenseFormat.decimal(summary.strengthLoad48h, 1), Modifier.weight(1f))
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("＋ Catat latihan") }
            OutlinedButton(onClick = onStrength, modifier = Modifier.weight(1f)) { Text("＋ Strength") }
        } }
        item { SectionKicker("AKTIVITAS TERBARU", "02") }
        item {
            val latest = activities.firstOrNull { it.type.equals("run", true) || it.type.equals("walk", true) }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (latest == null) EmptyCard("Belum ada lari tersimpan. Tambahkan sesi pertama untuk mulai membangun analisis beban.")
                else ActivityCard(latest, onClick = onViewAll)
                TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) { Text("Lihat semua riwayat", color = Lime) }
            }
        }
    }
}

@Composable
private fun WeeklyPlanHomeCard(snapshot: Snapshot?, settings: com.runnow.domain.Settings) {
    val days = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
    val phase = snapshot?.racePhase()
    val flag = snapshot?.flagInfo()
    val cutback = snapshot?.cutbackDue() == true
    val planned = PLAN_DAYS.map { d -> d to settings.plan[d] }
    val hasPlan = planned.any { it.second != null }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("RENCANA MINGGUAN", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(if (hasPlan) "Ritme latihanmu" else "Belum ada jadwal tetap", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                if (phase != null) {
                    Surface(color = Lime.copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
                        Text("${phase.k.uppercase()} · ${phase.days} hari", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (hasPlan) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    planned.forEach { (day, plan) ->
                        Column(Modifier.weight(1f).background(Ink, RoundedCornerShape(9.dp)).padding(vertical = 8.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(days[PLAN_DAYS.indexOf(day)], color = Muted, fontSize = 11.sp)
                            Text(when (plan?.t) { "easy" -> "E"; "quality" -> "Q"; "long" -> "L"; "rest" -> "R"; else -> if (plan == null) "—" else "A" }, color = if (plan == null || plan.t == "rest") Muted else Lime, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(if (plan == null || plan.km <= 0) " " else "%.1f".format(Locale.US, plan.km), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                Text("E easy · Q quality · L long · R istirahat · A otomatis", color = Muted, fontSize = 11.sp)
            } else {
                Text("Atur hari easy, quality, long run, atau istirahat melalui pengaturan rencana di Coach.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            if (cutback || flag != null || phase != null) {
                HorizontalDivider(color = Ink)
                if (cutback) Text("Cutback layak dipertimbangkan: long run tiga minggu terakhir relatif stabil. Turunkan beban, jangan menambah jarak.", color = Lime, fontSize = 11.sp, lineHeight = 15.sp)
                if (flag != null) Text(if (flag.lv == 2) "Penanda nyeri/kaki aktif · hari ${flag.dayN}/14. Hindari sesi quality dan evaluasi gejala." else "Penanda waspada kaki aktif · hari ${flag.dayN}/7. Pantau respons sebelum menaikkan beban.", color = Color(0xFFFFC66D), fontSize = 11.sp, lineHeight = 15.sp)
                if (phase != null) Text(when (phase.k) { "race" -> "Hari lomba. Prioritaskan kesiapan dan strategi lomba."; "taper" -> "Fase taper: kurangi volume, jaga kaki tetap segar."; "peak" -> "Fase peak: pertahankan sesi kunci tanpa menambah beban mendadak."; else -> "Fase build: bangun konsistensi dan progres bertahap." }, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}
@Composable
private fun TrainingScreen(
    activities: List<ActivityRecord>,
    strength: List<StrengthRecord>,
    profile: AthleteProfile,
    domainResult: com.runnow.domain.ProcessResult?,
    snapshot: Snapshot?,
    repository: RunSenseRepository,
    modifier: Modifier,
    onAdd: () -> Unit,
    onStrength: () -> Unit,
    onCheckin: (ActivityRecord) -> Unit
) {
    var filter by remember { mutableStateOf("week") }
    var mode by remember { mutableStateOf("run") }
    var selectedActivity by remember { mutableStateOf<ActivityRecord?>(null) }
    var visibleCount by remember { mutableIntStateOf(20) }
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

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageHero("RIWAYAT LATIHAN", "Every session", "has a story.", "Jejak lari, jalan, dan strength dalam satu tempat.") }
        item { RunSenseSegment(mode, listOf("run" to "Lari", "strength" to "Strength"), { mode = it; visibleCount = 20 }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("week" to "Minggu", "month" to "Bulan", "all" to "Semua").forEach { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key; visibleCount = 20 },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Ink)
                    )
                }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val values = if (mode == "run") listOf(
                        Triple(visibleRuns.size.toString(), "Sesi", Lime),
                        Triple(RunSenseFormat.distanceKm(totalKm, aggregate = true, withUnit = false), "km", White),
                        Triple(RunSenseFormat.durationMinutes(totalMinutes), "Durasi", White)
                    ) else listOf(
                        Triple(visibleStrength.size.toString(), "Catatan", Lime),
                        Triple(visibleStrength.count { it.completed }.toString(), "Selesai", White),
                        Triple(RunSenseFormat.durationWholeMinutes(visibleStrength.sumOf { it.durationMin }), "Durasi", White)
                    )
                    values.forEach { (value, label, color) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(label, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("＋ Aktivitas") }
                OutlinedButton(onClick = onStrength, modifier = Modifier.weight(1f)) { Text("＋ Strength") }
            }
        }
        if (mode == "run") {
            item { SectionTitle("Riwayat lari & jalan", "${visibleRuns.size} SESI") }
            if (visibleRuns.isEmpty()) item { EmptyCard("Belum ada aktivitas pada periode ini.") }
            groupedRuns.forEach { (weekStart, weekItems) ->
                item(key = "week-$weekStart") {
                    val km = weekItems.sumOf { it.distanceKm.takeIf { value -> value.isFinite() && value > 0.0 } ?: 0.0 }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                OutlinedButton(onClick = { visibleCount += 20 }, modifier = Modifier.fillMaxWidth()) { Text("Tampilkan lebih banyak") }
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
                OutlinedButton(onClick = { visibleCount += 20 }, modifier = Modifier.fillMaxWidth()) { Text("Tampilkan lebih banyak") }
            }
        }
    }

    selectedActivity?.let { activity ->
        val domainItem = domainResult?.activities?.firstOrNull {
            it.act.icuId == activity.id || it.act.lid == activity.id || it.act.start == activity.startedAt
        }
        val cadenceValue = remember(activity.id, snapshot) {
            val points = domainItem?.let { repository.streamPoints(it.act.icuId ?: it.act.lid) }.orEmpty()
            CadenceAnalyzer.analyze(points, 5).averageRunningCadence?.roundToInt()?.toString() ?: "—"
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

@Composable
private fun ProgressScreen(
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
        return "${sec / 60}:%02d".format(sec % 60)
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
            add("Perubahan proxy" to "${efficiencyChange?.let { "%+.1f".format(Locale.US, it) } ?: "—"}%")
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
        if (ctl.size >= 7) add("Kebugaran (CTL)" to "${ctl.first().ctl?.let { "%.1f".format(Locale.US, it) }} → ${ctl.last().ctl?.let { "%.1f".format(Locale.US, it) }}")
        val longestGap = validRuns.zipWithNext().maxOfOrNull { (a, b) -> (b.startedAt - a.startedAt) / dayMs }
        if (longestGap != null) add("Jeda antar lari terlama" to "${longestGap.roundToInt()} hari")
    }

    // 4. Durability: bandingkan sepertiga akhir dan tengah dari stream lari easy/long.
    data class DurSample(val date: Long, val paceDrift: Double, val hrDrift: Double?, val efficiencyDrop: Double?)
    val durability = validRuns.filter { it.distanceKm >= 5 || it.durationMin >= 40 }.mapNotNull { activity ->
        val act = actsById[activity.id] ?: return@mapNotNull null
        val kind = snapshot?.let { it.detectType(act, stage ?: it.stage()).type }
        if (kind !in setOf("easy", "long")) return@mapNotNull null
        val points = repository.streamPoints(activity.id).filter { it.timeSec.isFinite() && it.speedMps > 0.5 }
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
            add("Pace drift median" to "${median(durability.map { it.paceDrift })?.let { "%+.1f%%".format(Locale.US, it) }}")
            median(durability.mapNotNull { it.hrDrift })?.let { add("Perubahan HR median" to "${it.roundToInt()} bpm") }
            if (effDrop != null) add("Penurunan efisiensi" to "${ "%.1f%%".format(Locale.US, effDrop) }")
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
        PageHero("PERFORMANCE INTELLIGENCE", "See how far", "you've grown.", "Lima sisi dinilai terpisah, tanpa skor gabungan. Kesimpulan mengikuti kecukupan data.")
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
        ProgressDimensionCard("Kapasitas latihan", "Volume eksternal, minggu aktif, dan CTL bila tersedia", capacityStatus, confidence(validRuns.size, span), capacityRows, if (volumeDelta == null) "Riwayat minggu selesai belum cukup untuk membandingkan tren volume." else "Median jarak mingguan berubah ${ "%+.1f%%".format(Locale.US, volumeDelta) }. Minggu kosong tetap dihitung; kenaikan bukan target yang harus dipaksakan.")
        ProgressDimensionCard("Durability", "Kestabilan pace dan HR di sepertiga akhir lari panjang", durabilityStatus, confidence(durability.size, if (durability.size > 1) (durability.last().date - durability.first().date) / dayMs else 0.0), durabilityRows, if (durability.size < 3) "Butuh minimal 3 lari easy/long ≥5 km atau ≥40 menit dengan stream valid. Saat ini ${durability.size} sesi memenuhi syarat." else "Dibandingkan sepertiga tengah dengan sepertiga akhir. Elevasi, suhu, dan fueling belum dikontrol.")
        ProgressDimensionCard("Keberlanjutan", "Konsistensi latihan dan sinyal pemulihan dibanding baseline pribadi", sustainabilityStatus, if (latestWell.size >= 5 || recentStrength.size >= 3) "sedang" else "rendah", sustainabilityRows, if (recentRhr != null && baseRhr != null && recentRhr - baseRhr >= 3) "RHR terbaru ≥3 bpm di atas baseline. Ini sinyal untuk dipantau, bukan diagnosis." else "Data nyeri/cedera tidak tersedia lengkap; kartu ini tidak membuat diagnosis atau skor risiko cedera.")
        SectionTitle("Strength development", "28 HARI")
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

@Composable
private fun ProgressDimensionCard(
    title: String,
    subtitle: String,
    status: String,
    confidence: String,
    rows: List<Pair<String, String>>,
    insight: String
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(status, color = if (status in setOf("Membaik", "Stabil", "Konsisten")) Lime else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (expanded) "Tutup" else "Detail", color = Lime, fontSize = 11.sp)
                }
            }
            if (expanded) {
                if (rows.isEmpty()) Text("Belum ada data yang memenuhi syarat.", color = Muted, fontSize = 11.sp)
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(value, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                Divider(color = Color.White.copy(alpha = .08f))
                Text(insight, color = White, fontSize = 11.sp, lineHeight = 16.sp)
                Text("KEYAKINAN DATA · ${confidence}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
            }
        }
    }
}

@Composable
private fun WeeklyDistanceChart(activities: List<ActivityRecord>) {
    val today = java.time.LocalDate.now()
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = dates.map { date ->
        activities.filter { a ->
            a.type.equals("run", true) &&
                java.time.Instant.ofEpochMilli(a.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == date
        }.sumOf { it.distanceKm }
    }
    val maxKm = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Jarak lari", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${RunSenseFormat.distanceKm(values.sum(), aggregate = true)} total", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Canvas(Modifier.fillMaxWidth().height(104.dp)) {
                val slot = size.width / 7f
                val barWidth = slot * .46f
                values.forEachIndexed { index, value ->
                    val height = (value / maxKm * size.height * .86).toFloat()
                    val left = index * slot + (slot - barWidth) / 2f
                    drawRoundRect(
                        color = if (index == 6) Lime else Lime.copy(alpha = .38f),
                        topLeft = Offset(left, size.height - height),
                        size = Size(barWidth, height.coerceAtLeast(3f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                dates.forEach { date ->
                    Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("id", "ID")),
                        color = if (date == today) Lime else Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TrainingTrendChart(activities: List<ActivityRecord>) {
    var metric by remember { mutableStateOf("pace") }
    val today = java.time.LocalDate.now()
    val currentMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weeks = (3 downTo 0).map { offset ->
        val start = currentMonday.minusWeeks(offset.toLong())
        val end = start.plusWeeks(1)
        val runs = activities.filter { activity ->
            if (!activity.type.equals("run", true)) return@filter false
            val date = java.time.Instant.ofEpochMilli(activity.startedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            !date.isBefore(start) && date.isBefore(end)
        }
        val km = runs.sumOf { it.distanceKm }
        val paceValue = if (km > 0) runs.sumOf { it.durationMin } / km else null
        val hrValues = runs.filter { it.averageHr > 0 }.map { it.averageHr }
        val hrValue = hrValues.takeIf { it.isNotEmpty() }?.average()
        Triple(start, paceValue, hrValue)
    }
    val values = weeks.map { if (metric == "pace") it.second else it.third }
    val valid = values.filterNotNull()
    val minValue = valid.minOrNull() ?: 0.0
    val maxValue = valid.maxOrNull() ?: 1.0
    val range = (maxValue - minValue).coerceAtLeast(if (metric == "pace") 0.15 else 3.0)
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Tren 4 minggu", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(if (metric == "pace") "Pace rata-rata lari (menit/km)" else "Detak jantung rata-rata (bpm)", color = Muted, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(selected = metric == "pace", onClick = { metric = "pace" }, label = { Text("Pace") })
                    FilterChip(selected = metric == "hr", onClick = { metric = "hr" }, label = { Text("HR") })
                }
            }
            if (valid.isEmpty()) {
                Text("Belum cukup data lari untuk membentuk tren.", color = Muted, fontSize = 11.sp)
            } else {
                Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                    val slot = size.width / 4f
                    val barWidth = slot * .42f
                    values.forEachIndexed { index, value ->
                        if (value != null) {
                            val normalized = ((value - minValue) / range).toFloat().coerceIn(0f, 1f)
                            val height = (0.28f + normalized * .68f) * size.height
                            val left = index * slot + (slot - barWidth) / 2f
                            drawRoundRect(
                                color = if (index == 3) Lime else Lime.copy(alpha = .38f),
                                topLeft = Offset(left, size.height - height),
                                size = Size(barWidth, height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    weeks.forEachIndexed { index, week ->
                        val value = values[index]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("M${index + 1}", color = Muted, fontSize = 11.sp)
                            Text(
                                value?.let { if (metric == "pace") pace(it) else "${it.roundToInt()} bpm" } ?: "—",
                                color = if (index == 3) Lime else White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeartRateZoneChart(activities: List<ActivityRecord>, snapshot: Snapshot?) {
    val cutoff = System.currentTimeMillis() - 28L * 86_400_000L
    val sessions = activities.filter { it.type.equals("run", true) && it.startedAt >= cutoff }.mapNotNull { activity ->
        val zones = snapshot?.streams?.get(activity.id)?.tiz ?: return@mapNotNull null
        if (zones.size != 5) return@mapNotNull null
        zones to activity.durationMin.coerceAtLeast(1.0)
    }
    if (sessions.isEmpty()) {
        EmptyCard("Sebaran zona HR muncul setelah stream detak jantung tersedia dari Huawei atau Intervals.icu.")
        return
    }
    val totalMinutes = sessions.sumOf { it.second }
    val averages = (0..4).map { zone ->
        sessions.sumOf { it.first[zone] * it.second } / totalMinutes
    }
    val labels = listOf("Z1 · <60% HRR", "Z2 · 60–70% HRR", "Z3 · 70–80% HRR", "Z4 · 80–90% HRR", "Z5 · ≥90% HRR")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Waktu di zona HR", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${sessions.size} sesi · 28 hari", color = Muted, fontSize = 11.sp)
            }
            averages.forEachIndexed { index, value ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(labels[index], color = if (index == 1) Lime else White, fontSize = 11.sp)
                        Text("${value.roundToInt()}%", color = if (index == 1) Lime else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = (value / 100.0).toFloat().coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (index == 1) Lime else Lime.copy(alpha = 0.32f),
                        trackColor = Color.White.copy(alpha = 0.08f)
                    )
                }
            }
            Text("Rata-rata berbobot durasi dari stream sesi. Zona memakai Heart Rate Reserve (RHR–HRmax), bukan LTHR.", color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SessionReviewCard(item: ActivityItem, snapshot: Snapshot?, repository: RunSenseRepository) {
    val review = remember(item.act, snapshot) { snapshot?.reviewRun(item.act) }
    val streamKey = item.act.icuId ?: item.act.lid
    val hasStream = snapshot?.streams?.containsKey(streamKey) == true
    val rawPoints = remember(item.act.lid, snapshot) { repository.streamPoints(streamKey) }
    val cadence = remember(rawPoints) { CadenceAnalyzer.analyze(rawPoints, 5) }
    var cadenceExpanded by remember(item.act.lid) { mutableStateOf(false) }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(review?.summary?.ifBlank { item.why } ?: item.why, color = White, fontSize = 12.sp, lineHeight = 17.sp)
            review?.sections?.take(3)?.forEachIndexed { index, section ->
                DisclosureSection(section.title, initiallyExpanded = index == 0) {
                    section.notes.take(3).forEach { note ->
                        Text("• ${note.text}", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            if (rawPoints.isNotEmpty()) {
                Divider(color = Color.White.copy(alpha = .08f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Kadens per 5 detik", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${cadence.windows.size} segmen · ${cadence.runningWindows} lari · ${cadence.walkingWindows} jalan", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { cadenceExpanded = !cadenceExpanded }) {
                        Text(if (cadenceExpanded) "Tutup" else "Lihat")
                    }
                }
                Text("Rata-rata kadens saat lari: ${cadence.averageRunningCadence?.let { "$it spm" } ?: "—"}", color = White, fontSize = 11.sp)
                if (cadenceExpanded) {
                    cadence.windows.forEach { w ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(CadenceAnalyzer.formatTime(w.startSec), color = Muted, fontSize = 11.sp)
                            Text(w.cadenceSpm?.let { "${it.roundToInt()} spm" } ?: "—", color = White, fontSize = 11.sp)
                            Text(w.mode, color = if (w.mode == "Lari") Lime else Muted, fontSize = 11.sp)
                            Text(CadenceAnalyzer.formatPace(w.paceSecPerKm), color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            } else {
                Text("Stream sesi belum tersedia. Data stream diambil otomatis saat sinkronisasi; coba sinkronkan ulang jika sesi ini belum memiliki stream.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun WeeklyReviewCard(review: WeeklyReview) {
    val ratio = if (review.targetKm > 0) (review.actualKm / review.targetKm).coerceIn(0.0, 1.0).toFloat() else 0f
    Surface(color = Panel, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("RINGKASAN PELAKSANAAN", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("%.1f km".format(Locale.US, review.actualKm), color = Lime, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("realisasi hingga hari ini", color = Muted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("%.1f km".format(Locale.US, review.targetKm), color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("target yang jatuh tempo", color = Muted, fontSize = 11.sp)
                }
            }
            LinearProgressIndicator(progress = ratio, modifier = Modifier.fillMaxWidth().height(7.dp), color = Lime, trackColor = Color.White.copy(alpha = .10f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Kepatuhan sesi: ${review.adherencePercent?.let { "$it%" } ?: "belum ada sesi jatuh tempo"}", color = White, fontSize = 11.sp)
                Text("${review.completedSessions}/${review.dueSessions} selesai", color = Muted, fontSize = 11.sp)
            }
            Divider(color = Color.White.copy(alpha = .08f))
            Text("ARAH MINGGU DEPAN", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(review.adjustment, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(review.rationale, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Target rencana berikutnya", color = Muted, fontSize = 11.sp)
                Text("%.1f km".format(Locale.US, review.nextWeekTargetKm), color = Lime, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (review.extraKm > 0.2) {
                Text("Ada %.1f km di luar target harian. Pastikan penambahan spontan ini tidak menumpuk dengan sesi berat.".format(Locale.US, review.extraKm), color = Color(0xFFFFC16B), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun CoachScreen(activities: List<ActivityRecord>, summary: TrainingSummary, strength: List<StrengthRecord>, strengthTarget: Int, parqAnswers: List<Boolean?>, domainResult: com.runnow.domain.ProcessResult?, snapshot: Snapshot?, planSettings: com.runnow.domain.Settings, weather: WeatherSnapshot?, weatherMessage: String, modifier: Modifier, onRefreshWeather: () -> Unit, onWorkout: () -> Unit) {
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
            MetricCard("ACWR", summary.acwr?.let { "%.2f".format(Locale.US, it) } ?: "Belum cukup data beban kronis", Modifier.fillMaxWidth())
        }
        val weekStart = java.time.LocalDate.now().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        val weekEnd = weekStart.plusDays(6)
        val checkins7d = activities.count { item ->
            item.type.equals("run", true) && (item.rpe > 0 || item.pain > 0 || item.feel.isNotBlank()) &&
                java.time.Instant.ofEpochMilli(item.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { !it.isBefore(weekStart) && !it.isAfter(weekEnd) }
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
                val km = if (suggested.targetKm > 0) " · %.1f km".format(Locale.US, suggested.targetKm) else ""
                InfoCard("$dayName · ${suggested.date.dayOfMonth}/${suggested.date.monthValue}", suggested.title + km,
                    if (suggested.title.startsWith("Easy ringan")) "DIUBAH COACH" else "DIPERTAHANKAN")
            }
            SectionTitle("Rincian per hari", "TARGET · AKTUAL")
            weekReview.days.forEach { day ->
                val dayName = day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("id", "ID"))
                val target = if (day.targetKm > 0) "target %.1f km".format(Locale.US, day.targetKm) else "target sesi"
                val actual = if (day.actualKm > 0) "aktual %.1f km".format(Locale.US, day.actualKm) else "belum ada lari"
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
private fun SettingsScreen(
    profile: AthleteProfile, wellness: List<WellnessRecord>, modifier: Modifier,
    strengthTarget: Int, parqAnswers: List<Boolean?>, onStrengthTarget: () -> Unit, onParq: () -> Unit,
    hasIntervalsKey: Boolean, syncBusy: Boolean, syncMessage: String,
    onProfile: () -> Unit, onWellness: () -> Unit, onPlanRace: () -> Unit, onApiKey: () -> Unit, onSync: () -> Unit,
    onCoach: () -> Unit, onWatch: () -> Unit,
    runWalk: RunWalkSettings, lightTheme: Boolean, coachingStyle: String,
    onRunWalk: (RunWalkSettings) -> Unit, onTheme: (Boolean) -> Unit, onStyle: (String) -> Unit
) {
    Page(modifier) {
        PageHero("PROFIL ATLET", "Your", "profile.", "Atur data tubuh, target, jadwal latihan, dan koneksi data.")
        SectionTitle("Tampilan", "PERSONALISASI")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tema terang", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (lightTheme) "Aktif" else "Gelap · default", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = lightTheme, onCheckedChange = onTheme)
        }
        SectionTitle("Gaya coaching", "PREFERENSI COACH")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("conservative" to "Konservatif", "moderate" to "Moderat", "aggressive" to "Agresif").forEach { item ->
                FilterChip(selected = coachingStyle == item.first, onClick = { onStyle(item.first) }, label = { Text(item.second, fontSize = 11.sp) })
            }
        }
        SectionTitle("Pola lari–jalan", "SEMUA TAHAP")
        RunWalkSettingsCard(runWalk, onRunWalk)
        SectionTitle("Ruang latihan", "FITUR RUNSENSE")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCoach, modifier = Modifier.weight(1f)) { Text("✦ Coach") }
            OutlinedButton(onClick = onWatch, modifier = Modifier.weight(1f)) { Text("⌚ Jam") }
        }
        InfoCard("Profil detak jantung", "RHR ${profile.restingHr} · HRmax ${profile.maxHr} · LTHR ${profile.thresholdHr}", "EDIT")
        InfoCard("Data tubuh", "TB ${if (profile.heightCm > 0) "${profile.heightCm} cm" else "belum diisi"} · BB ${if (profile.weightKg > 0) RunSenseFormat.kg(profile.weightKg) else "belum diisi"}", "PROFIL")
        Button(onClick = onProfile, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("Atur profil") }
        SectionTitle("Rencana dan target race", "TERSIMPAN LOKAL")
        OutlinedButton(onClick = onPlanRace, modifier = Modifier.fillMaxWidth()) { Text("Atur rencana mingguan & race") }
        SectionTitle("Sinkronisasi Intervals.icu", if (hasIntervalsKey) "TERHUBUNG" else "BELUM TERHUBUNG")
        Text(if (hasIntervalsKey) "API key tersimpan secara lokal. Sinkronkan aktivitas dan wellness dari akun Intervals.icu." else "Masukkan API key Intervals.icu untuk mengambil riwayat aktivitas dan wellness.")
        Button(onClick = onApiKey, colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Lime)) { Text(if (hasIntervalsKey) "Ganti API key" else "Masukkan API key") }
        OutlinedButton(onClick = onSync, enabled = hasIntervalsKey && !syncBusy) { Text(if (syncBusy) "Menyinkronkan…" else "Sinkronkan sekarang") }
        if (syncMessage.isNotBlank()) Text(syncMessage, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        SectionTitle("Strength", "TARGET MINGGUAN")
        InfoCard("Target strength", "$strengthTarget hari per minggu", "COACH")
        OutlinedButton(onClick = onStrengthTarget, modifier = Modifier.fillMaxWidth()) { Text("Atur target strength mingguan") }
        SectionTitle("Skrining aktivitas", "PAR-Q · 7 PERTANYAAN")
        val yesCount = parqAnswers.count { it == true }
        val answered = parqAnswers.count { it != null }
        InfoCard("Status PAR-Q", if (answered < 7) "$answered/7 pertanyaan terisi" else if (yesCount > 0) "$yesCount jawaban YA — perlu tindak lanjut" else "Semua jawaban TIDAK", if (yesCount > 0) "PERHATIAN" else "SKRINING")
        OutlinedButton(onClick = onParq, modifier = Modifier.fillMaxWidth()) { Text("Isi / perbarui PAR-Q") }
        if (yesCount > 0) Text("Ada jawaban YA. Konsultasikan dengan tenaga kesehatan sebelum memulai atau meningkatkan latihan.", color = Color(0xFFFFC36A), fontSize = 11.sp, lineHeight = 16.sp)
        var showAllWellness by remember(wellness) { mutableStateOf(false) }
        val wellnessToday = java.time.LocalDate.now()
        val validWellness = wellness.filter { item ->
            val date = runCatching { java.time.LocalDate.parse(item.date) }.getOrNull()
            date != null && !date.isAfter(wellnessToday) &&
                (item.restingHr > 0 || RunSenseFormat.valid(item.sleepHours) != null || RunSenseFormat.valid(item.hrv) != null)
        }.sortedByDescending { it.date }
        val recentWellness = validWellness.filter {
            runCatching { java.time.LocalDate.parse(it.date) }.getOrNull()?.let { date -> !date.isBefore(wellnessToday.minusDays(2)) } == true
        }
        val wellnessRows = if (showAllWellness) validWellness else recentWellness
        SectionTitle("Wellness", "${validWellness.size} HARI VALID")
        if (wellnessRows.isEmpty()) EmptyCard("Belum ada data wellness valid dalam 3 hari terakhir.")
        wellnessRows.forEach { item ->
            InfoCard(
                RunSenseFormat.strengthDate(item.date),
                "RHR ${RunSenseFormat.bpm(item.restingHr)} bpm · tidur ${RunSenseFormat.sleepHours(item.sleepHours)} · HRV ${RunSenseFormat.integer(item.hrv)}",
                "TERSIMPAN"
            )
        }
        if (validWellness.size > 3) {
            TextButton(onClick = { showAllWellness = !showAllWellness }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAllWellness) "Tampilkan 3 hari terakhir" else "Lihat semua (${validWellness.size} hari)", color = Lime)
            }
        }
        OutlinedButton(onClick = onWellness) { Text("＋ Catat wellness hari ini") }
        Text("Data aktivitas dan wellness dari Huawei serta Intervals digabung ke riwayat lokal. API key disimpan di penyimpanan privat aplikasi.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun HomePageHero() {
    val hour = java.time.LocalTime.now().hour
    val greeting = when (hour) {
        in 4..10 -> "Selamat pagi"
        in 11..14 -> "Selamat siang"
        in 15..17 -> "Selamat sore"
        else -> "Selamat malam"
    }
    val date = SimpleDateFormat("EEEE, dd MMMM", Locale("id", "ID")).format(Date())
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR DAILY RUNNING BRIEF", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Surface(color = Lime, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("R", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy((-3).dp)) {
            Text("Run with", color = White, fontSize = 36.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.4).sp)
            Text("your rhythm.", color = Lime, fontSize = 36.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.4).sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(greeting, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("·", color = Muted, fontSize = 12.sp)
            Text(date, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PageHero(eyebrow: String, title: String, accent: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.45.sp)
        Column(verticalArrangement = Arrangement.spacedBy((-3).dp)) {
            Text(title, color = White, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.1).sp)
            Text(accent, color = Lime, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.1).sp)
        }
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SectionKicker(label: String, index: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.35.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = .10f)))
        Text(index, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeWeatherSummary(weather: WeatherSnapshot?, onRefresh: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CUACA HARI INI", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                val summary = when {
                    weather == null -> "Belum tersedia"
                    weather.temperatureC >= 30.0 -> "${RunSenseFormat.decimal(weather.temperatureC, 1)}°C · Longgarkan pace"
                    weather.precipitationMm >= 0.3 -> "${RunSenseFormat.decimal(weather.temperatureC, 1)}°C · Waspadai hujan"
                    else -> "${RunSenseFormat.decimal(weather.temperatureC, 1)}°C · Kondisi relatif nyaman"
                }
                Text(summary, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onRefresh) { Text("Perbarui", color = Lime, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun WeatherCard(weather: WeatherSnapshot?, message: String, onRefresh: () -> Unit) {
    val status = when {
        weather == null -> "BELUM DIMUAT"
        weather.weatherCode >= 95 -> "PETIR"
        weather.precipitationMm >= 2.0 -> "HUJAN LEBAT"
        weather.precipitationMm >= 0.3 -> "HUJAN"
        weather.temperatureC >= 30.0 -> "PANAS"
        weather.temperatureC >= 25.0 -> "HANGAT"
        else -> "RELATIF SEJUK"
    }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("CUACA & KONTEKS LARI", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                Text(status, color = if (weather?.let { it.temperatureC >= 30.0 || it.precipitationMm >= 2.0 || it.weatherCode >= 95 } == true) Color(0xFFFFC36A) else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (weather == null) {
                Text("Cuaca belum tersedia. Perbarui dengan lokasi perangkat untuk membaca suhu dan hujan.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            } else {
                Text("${RunSenseFormat.decimal(weather.temperatureC, 1)}°C", color = White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Terasa ${RunSenseFormat.decimal(weather.apparentC, 1)}°C · RH ${weather.humidityPct}% · angin ${RunSenseFormat.decimal(weather.windKmh, 1)} km/j · hujan ${RunSenseFormat.decimal(weather.precipitationMm, 1)} mm", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                if (weather.temperatureC >= 30.0) Text("Panas terdeteksi: turunkan tuntutan pace sekitar 6% dan gunakan HR/RPE sebagai panduan.", color = Color(0xFFFFC36A), fontSize = 11.sp, lineHeight = 15.sp)
                Text("Open-Meteo · lokasi perangkat · ${SimpleDateFormat("HH:mm", Locale("id", "ID")).format(Date(weather.fetchedAt))}", color = Muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Perbarui cuaca") }
            }
        }
    }
}

@Composable
private fun CoachRecommendationCard(recommendation: com.runnow.domain.Recommendation?, onWorkout: () -> Unit) {
    if (recommendation == null) return
    Surface(color = Panel, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(26.dp))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("REKOMENDASI COACH · ${recommendation.tag}", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(recommendation.title, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(listOf(recommendation.duration, recommendation.hr, recommendation.pace).filter { it.isNotBlank() && it != "—" }.joinToString(" · "), color = Lime, fontSize = 11.sp)
            Text(recommendation.reason, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            recommendation.steps.forEach { step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(step.first, color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(step.second, color = Muted, fontSize = 11.sp)
                }
            }
            Button(
                onClick = onWorkout,
                enabled = recommendation.status == "train" && recommendation.steps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
            ) { Text("▶  Kerjakan di jam") }
        }
    }
}

@Composable
private fun ReadinessCard(score: Int?, recommendation: String) {
    Surface(color = Panel, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(26.dp))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("READINESS HARI INI", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(if (score == null) "DATA TERBATAS" else if (score < 45) "PEMULIHAN" else if (score < 65) "KONTROL" else "SIAP TERPANTAU",
                    color = if (score != null && score < 45) Color(0xFFFFB65C) else Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(82.dp)) {
                    CircularProgressIndicator(progress = ((score ?: 0).coerceIn(0, 100) / 100f), modifier = Modifier.fillMaxSize(),
                        color = if (score != null && score < 45) Color(0xFFFFB65C) else Lime, trackColor = Color.White.copy(alpha = .10f), strokeWidth = 7.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(score?.toString() ?: "—", color = White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                        Text("/100", color = Muted, fontSize = 11.sp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (score == null) "Menunggu data" else if (score < 45) "Fokus pulih" else if (score < 65) "Jaga intensitas" else "Ikuti rencana dengan kontrol effort",
                        color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(recommendation, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = modifier.border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(16.dp))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(action, color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckinActivityCard(activity: ActivityRecord, onCheckin: (ActivityRecord) -> Unit) {
    val stamp = SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID")).format(Date(activity.startedAt))
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(20.dp))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Lari · $stamp", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${RunSenseFormat.durationMinutes(activity.durationMin)} · ${RunSenseFormat.distanceKm(activity.distanceKm)} · HR ${RunSenseFormat.bpm(activity.averageHr)}", color = Muted, fontSize = 11.sp)
                }
                Text(if (activity.quality) "QUALITY" else "RUN", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (activity.rpe > 0 || activity.feel.isNotBlank() || activity.pain > 0) {
                Text("RPE ${activity.rpe}/10 · ${activity.feel.ifBlank { "feel belum diisi" }} · nyeri ${activity.pain}/10", color = Muted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = { onCheckin(activity) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (activity.rpe > 0 || activity.feel.isNotBlank() || activity.pain > 0) "Ubah check-in" else "Isi check-in RPE / nyeri")
            }
        }
    }
}

@Composable
private fun ActivityCard(a: ActivityRecord, profile: AthleteProfile = AthleteProfile(), onClick: () -> Unit = {}) {
    val stamp = SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID")).format(Date(a.startedAt))
    Surface(
        color = Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${RunSenseFormat.activityType(a.type)} · $stamp", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${RunSenseFormat.durationMinutes(a.durationMin)} · ${RunSenseFormat.distanceKm(a.distanceKm)} · HR ${RunSenseFormat.bpm(a.averageHr)}", color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TL ${TrainingEngine.activityLoad(a, profile).roundToInt()}", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("LIHAT ›", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String, tag: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Muted, fontSize = 11.sp)
            }
            Text(tag, color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ActivityDialog(onDismiss: () -> Unit, onSave: (String, Double, Double, Int, Double, Boolean, Int, String, Int) -> Unit) {
    var type by remember { mutableStateOf("run") }
    var duration by remember { mutableStateOf("30") }
    var distance by remember { mutableStateOf("3") }
    var hr by remember { mutableStateOf("") }
    var load by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(false) }
    var rpe by remember { mutableFloatStateOf(0f) }
    var feel by remember { mutableStateOf("") }
    var pain by remember { mutableFloatStateOf(0f) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Catat aktivitas") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(type == "run", { type = "run" }, label = { Text("Lari") })
                FilterChip(type == "walk", { type = "walk" }, label = { Text("Jalan") })
            }
            NumberField("Durasi (menit)", duration) { duration = it }
            NumberField("Jarak (km)", distance) { distance = it }
            NumberField("Rata-rata HR (opsional)", hr) { hr = it }
            NumberField("Training load eksternal (opsional)", load) { load = it }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(quality, { quality = it }); Text("Sesi quality / intensitas") }
            if (type == "run") {
                Divider(color = Color.White.copy(alpha = .12f))
                Text("Check-in setelah lari", color = Lime, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("RPE · seberapa berat sesi terasa?  ${rpe.roundToInt()}/10", color = White, fontSize = 11.sp)
                Slider(value = rpe, onValueChange = { rpe = it }, valueRange = 0f..10f, steps = 9)
                Text("Bagaimana rasanya dibanding perkiraan?", color = White, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("lebih ringan", "sesuai").forEach { item ->
                        FilterChip(selected = feel == item, onClick = { feel = item }, label = { Text(item, fontSize = 11.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("lebih berat", "sangat berat").forEach { item ->
                        FilterChip(selected = feel == item, onClick = { feel = item }, label = { Text(item, fontSize = 11.sp) })
                    }
                }
                Text("Nyeri saat/ setelah lari: ${pain.roundToInt()}/10", color = White, fontSize = 11.sp)
                Slider(value = pain, onValueChange = { pain = it }, valueRange = 0f..10f, steps = 9)
                Text("0 = tidak nyeri · 10 = nyeri sangat berat. Hentikan latihan bila nyeri mengganggu gerak.", color = Muted, fontSize = 11.sp)
            }
        } },
        confirmButton = { TextButton(onClick = {
            val d = duration.toDoubleOrNull() ?: 0.0
            val km = distance.toDoubleOrNull() ?: 0.0
            if (d > 0 && (type == "walk" || km > 0)) onSave(type, d, km, hr.toIntOrNull() ?: 0, load.toDoubleOrNull() ?: 0.0, quality, if (type == "run") rpe.roundToInt() else 0, if (type == "run") feel else "", if (type == "run") pain.roundToInt() else 0)
        }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
private fun SessionCheckinDialog(
    activity: ActivityRecord,
    onDismiss: () -> Unit,
    onSave: (Int, String, Int) -> Unit,
) {
    var rpe by remember(activity.id) { mutableFloatStateOf(activity.rpe.toFloat()) }
    var feel by remember(activity.id) { mutableStateOf(activity.feel) }
    var pain by remember(activity.id) { mutableFloatStateOf(activity.pain.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check-in setelah lari") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Seberapa berat sesi terasa? RPE ${rpe.roundToInt()}/10", color = White, fontSize = 12.sp)
                Slider(value = rpe, onValueChange = { rpe = it }, valueRange = 0f..10f, steps = 9)
                Text("Dibanding perkiraan awal", color = White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("lebih ringan", "sesuai").forEach { item ->
                        FilterChip(selected = feel == item, onClick = { feel = item }, label = { Text(item, fontSize = 11.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("lebih berat", "sangat berat").forEach { item ->
                        FilterChip(selected = feel == item, onClick = { feel = item }, label = { Text(item, fontSize = 11.sp) })
                    }
                }
                Text("Nyeri saat / setelah lari: ${pain.roundToInt()}/10", color = White, fontSize = 12.sp)
                Slider(value = pain, onValueChange = { pain = it }, valueRange = 0f..10f, steps = 9)
                Text("Jika nyeri mengubah langkah atau bertambah saat berlari, jangan memaksakan sesi berikutnya.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(rpe.roundToInt(), feel, pain.roundToInt()) }) { Text("Simpan check-in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun StrengthDialog(onDismiss: () -> Unit, onSave: (String, Int, Int, Boolean, String, Int, Int, Double) -> Unit) {
    var category by remember { mutableStateOf("leg") }
    var exercise by remember { mutableStateOf("Squat") }
    var duration by remember { mutableStateOf("20") }
    var level by remember { mutableStateOf("1") }
    var sets by remember { mutableStateOf("3") }
    var reps by remember { mutableStateOf("10") }
    var load by remember { mutableStateOf("0") }
    val exercises = when (category) {
        "leg" -> listOf("Squat", "Lunge", "Step-up", "Wall sit")
        "hip" -> listOf("Glute bridge", "Clamshell", "Hip thrust", "Single-leg bridge")
        "calf" -> listOf("Calf raise", "Single-leg calf raise", "Tibialis raise")
        "core" -> listOf("Plank", "Side plank", "Dead bug", "Bird dog")
        else -> listOf("A-skip", "High knees", "Butt kicks", "Drill teknik")
    }
    if (exercise !in exercises) exercise = exercises.first()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Catat strength") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kelompok otot", color = Muted)
            listOf("leg" to "Kaki", "hip" to "Pinggul", "calf" to "Betis", "core" to "Core", "drill" to "Drill").forEach { (key, label) ->
                FilterChip(category == key, { category = key }, label = { Text(label) })
            }
            Text("Gerakan", color = Muted)
            exercises.forEach { name -> FilterChip(exercise == name, { exercise = name }, label = { Text(name) }) }
            NumberField("Durasi (menit)", duration) { duration = it }
            NumberField("Set", sets) { sets = it }
            NumberField("Repetisi per set", reps) { reps = it }
            NumberField("Beban eksternal (kg, opsional)", load) { load = it }
            NumberField("Level effort 0–4", level) { level = it }
        } },
        confirmButton = { TextButton(onClick = { onSave(category, (duration.toIntOrNull() ?: 0).coerceAtLeast(0), (level.toIntOrNull() ?: 0).coerceIn(0,4), true, exercise, (sets.toIntOrNull() ?: 0).coerceAtLeast(0), (reps.toIntOrNull() ?: 0).coerceAtLeast(0), (load.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)) }) { Text("Simpan sesi") } },
        dismissButton = { TextButton(onClick = { onSave(category, (duration.toIntOrNull() ?: 0), (level.toIntOrNull() ?: 0).coerceIn(0,4), false, exercise, 0, 0, 0.0) }) { Text("Skip") } })
}

@Composable
private fun StrengthTargetDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var days by remember(current) { mutableIntStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Target strength mingguan") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$days hari per minggu", color = Lime, fontWeight = FontWeight.Bold)
            Slider(value = days.toFloat(), onValueChange = { days = it.roundToInt() }, valueRange = 0f..7f, steps = 6)
        } },
        confirmButton = { TextButton(onClick = { onSave(days) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
private fun ParqDialog(current: List<Boolean?>, onDismiss: () -> Unit, onSave: (List<Boolean?>) -> Unit) {
    val questions = listOf(
        "Dokter pernah menyatakan Anda memiliki kondisi jantung dan hanya boleh berolahraga sesuai rekomendasi tenaga kesehatan?",
        "Apakah Anda merasakan nyeri dada saat melakukan aktivitas fisik?",
        "Dalam sebulan terakhir, pernahkah nyeri dada muncul saat tidak beraktivitas fisik?",
        "Pernahkah Anda kehilangan keseimbangan karena pusing atau kehilangan kesadaran?",
        "Apakah Anda memiliki masalah tulang atau sendi yang dapat memburuk akibat perubahan aktivitas fisik?",
        "Apakah dokter saat ini meresepkan obat untuk tekanan darah atau kondisi jantung?",
        "Adakah alasan kesehatan lain yang membuat aktivitas fisik tidak aman bagi Anda?"
    )
    val answers = remember(current) { mutableStateListOf<Boolean?>().apply { addAll(current) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("PAR-Q · skrining awal") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Skrining awal, bukan diagnosis atau izin medis. Jawab semua pertanyaan.", color = Muted, fontSize = 11.sp)
            questions.forEachIndexed { index, question ->
                Text("${index + 1}. $question", color = White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(answers[index] == true, { answers[index] = true }, label = { Text("YA") })
                    FilterChip(answers[index] == false, { answers[index] = false }, label = { Text("TIDAK") })
                }
            }
            if (answers.any { it == true }) Text("Ada jawaban YA. Konsultasikan dengan tenaga kesehatan sebelum memulai atau meningkatkan latihan. Nyeri dada akut, pingsan, atau sesak berat memerlukan pertolongan segera.", color = Color(0xFFFFC36A), fontSize = 11.sp)
        } },
        confirmButton = { TextButton(onClick = { onSave(answers.toList()) }, enabled = answers.all { it != null }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
private fun PlanRaceDialog(
    current: com.runnow.domain.Settings,
    onDismiss: () -> Unit,
    onSave: (com.runnow.domain.Settings) -> Unit,
) {
    val dayKeys = listOf(1, 2, 3, 4, 5, 6, 0)
    val dayNames = listOf("Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu", "Minggu")
    val titles = remember(current) {
        mutableStateListOf<String>().apply {
            addAll(dayKeys.map { current.plan[it]?.t ?: "" })
        }
    }
    val distances = remember(current) {
        mutableStateListOf<String>().apply {
            addAll(dayKeys.map { current.plan[it]?.km?.takeIf { km -> km > 0 }?.toString() ?: "" })
        }
    }
    var raceDate by remember(current) { mutableStateOf(current.race) }
    var raceDistance by remember(current) { mutableStateOf(if (current.raceDist > 0) current.raceDist.toString() else "") }
    var raceGoal by remember(current) { mutableStateOf(current.raceGoal) }
    var goal by remember(current) { mutableStateOf(current.goal) }
    var style by remember(current) { mutableStateOf(current.style) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rencana mingguan & race") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("Rencana latihan", color = Lime, fontWeight = FontWeight.Bold)
                Text("Isi nama sesi dan target kilometer. Kosongkan hari istirahat.", color = Muted, fontSize = 11.sp)
                dayKeys.forEachIndexed { index, day ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(dayNames[index], color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = titles[index], onValueChange = { titles[index] = it },
                                modifier = Modifier.weight(1f), singleLine = true,
                                label = { Text("Sesi (Easy/Interval/Rest)") }
                            )
                            OutlinedTextField(
                                value = distances[index], onValueChange = { distances[index] = it },
                                modifier = Modifier.width(92.dp), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                label = { Text("Km") }
                            )
                        }
                    }
                }
                Divider(color = Color.White.copy(alpha = .12f))
                Text("Target race", color = Lime, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = raceDate, onValueChange = { raceDate = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Tanggal (YYYY-MM-DD)") }
                )
                TextButton(onClick = { raceDate = "" }) { Text("Hapus tanggal lomba") }
                OutlinedTextField(
                    value = raceDistance, onValueChange = { raceDistance = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Jarak race (km)") }
                )
                OutlinedTextField(
                    value = raceGoal, onValueChange = { raceGoal = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Target waktu (mm:ss atau h:mm:ss)") }
                )
                Text("Fokus latihan", color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("5K", "10K", "Half", "Marathon", "Umum").forEach { item ->
                        FilterChip(selected = goal == item, onClick = { goal = item }, label = { Text(item) })
                    }
                }
                Text("Gaya penyesuaian", color = Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("conservative" to "Konservatif", "moderate" to "Moderat", "aggressive" to "Agresif").forEach { item ->
                        FilterChip(selected = style == item.first, onClick = { style = item.first }, label = { Text(item.second) })
                    }
                }
                Text("Coach menggunakan rencana ini untuk membandingkan target dengan aktivitas yang benar-benar tersimpan. Menyimpan tidak mengubah riwayat latihan.", color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val plan = LinkedHashMap<Int, com.runnow.domain.PlanDay>()
                dayKeys.forEachIndexed { index, day ->
                    val title = titles[index].trim()
                    if (title.isNotBlank()) {
                        plan[day] = com.runnow.domain.PlanDay(title, (distances[index].toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0))
                    }
                }
                val validRaceDate = raceDate.trim().takeIf {
                    it.isBlank() || runCatching { java.time.LocalDate.parse(it) }.isSuccess
                } ?: current.race
                onSave(current.copy(
                    style = style,
                    plan = plan,
                    race = validRaceDate,
                    raceDist = (raceDistance.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                    raceGoal = raceGoal.trim(),
                    goal = goal,
                ))
            }) { Text("Simpan rencana") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun WellnessDialog(onDismiss: () -> Unit, onSave: (Int, Double, Double) -> Unit) {
    var rhr by remember { mutableStateOf("60") }
    var sleep by remember { mutableStateOf("7") }
    var hrv by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Wellness hari ini") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Resting HR (bpm)", rhr) { rhr = it }
            NumberField("Tidur (jam)", sleep) { sleep = it }
            NumberField("HRV (opsional)", hrv) { hrv = it }
        } },
        confirmButton = { TextButton(onClick = { onSave(rhr.toIntOrNull() ?: 0, sleep.toDoubleOrNull() ?: 0.0, hrv.toDoubleOrNull() ?: 0.0) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
private fun ProfileDialog(profile: AthleteProfile, onDismiss: () -> Unit, onSave: (AthleteProfile) -> Unit) {
    var rhr by remember { mutableStateOf(profile.restingHr.toString()) }
    var max by remember { mutableStateOf(profile.maxHr.toString()) }
    var lt by remember { mutableStateOf(profile.thresholdHr.toString()) }
    var pace by remember { mutableStateOf("%d:%02d".format(profile.thresholdPaceSecPerKm / 60, profile.thresholdPaceSecPerKm % 60)) }
    var height by remember { mutableStateOf(profile.heightCm.takeIf { it > 0 }?.toString() ?: "") }
    var weight by remember { mutableStateOf(profile.weightKg.takeIf { it > 0 }?.toString() ?: "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Profil fisiologis & tubuh") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("RHR (bpm)", rhr) { rhr = it }
            NumberField("HRmax (bpm)", max) { max = it }
            NumberField("LTHR (bpm)", lt) { lt = it }
            OutlinedTextField(pace, { pace = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("LT pace (mm:ss/km)") })
            NumberField("Tinggi badan (cm)", height) { height = it }
            NumberField("Berat badan (kg)", weight) { weight = it }
        } },
        confirmButton = { TextButton(onClick = { onSave(AthleteProfile(
            rhr.toIntOrNull() ?: 63, max.toIntOrNull() ?: 185, lt.toIntOrNull() ?: 160,
            run {
                val parts = pace.trim().replace(',', ':').split(":")
                if (parts.size == 2) ((parts[0].toIntOrNull() ?: 6) * 60 + (parts[1].toIntOrNull() ?: 30)).coerceIn(120, 1200)
                else (pace.toIntOrNull() ?: 390).coerceIn(120, 1200)
            }, height.toIntOrNull()?.coerceIn(100, 230) ?: 0,
            weight.toDoubleOrNull()?.takeIf { it in 25.0..250.0 } ?: 0.0
        )) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
private fun WorkoutComposerDialog(
    recommendation: com.runnow.domain.Recommendation?,
    onDismiss: () -> Unit,
    onSend: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    val rec = recommendation
    var name by remember(rec) { mutableStateOf(rec?.title ?: "Latihan lari") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var description by remember(rec) { mutableStateOf(rec?.let { recommendationWorkoutText(it) }.orEmpty()) }
    val canSend = rec?.status == "train" && description.isNotBlank() && runCatching { java.time.LocalDate.parse(date) }.isSuccess

    fun workoutText(): String = buildString {
        append(name.trim().ifBlank { "Latihan lari" })
        append("\n")
        append(date.trim())
        append("\n\n")
        append(description.trim())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kerjakan di jam", color = White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("Susun latihan dari rekomendasi Coach, lalu kirim ke kalender Intervals.icu atau bagikan teksnya.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                OutlinedTextField(name, { name = it }, label = { Text("Nama latihan") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(date, { date = it }, label = { Text("Tanggal (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    description, { description = it },
                    label = { Text("Struktur workout Intervals.icu") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 7,
                    maxLines = 12
                )
                if (rec?.status != "train") {
                    Text("Coach tidak merekomendasikan sesi lari hari ini. Pengiriman workout dinonaktifkan.", color = Color(0xFFFFC36A), fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("RunSense workout", workoutText()))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Salin teks") }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, name)
                                putExtra(Intent.EXTRA_TEXT, workoutText())
                            }
                            context.startActivity(Intent.createChooser(intent, "Bagikan workout"))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Bagikan") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(name, date, description) }, enabled = canSend) {
                Text("Kirim ke Intervals.icu", color = if (canSend) Lime else Muted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

private fun recommendationWorkoutText(recommendation: com.runnow.domain.Recommendation): String {
    recommendation.wl?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n").trim() }
    return recommendation.steps.mapNotNull { (label, detail) ->
        val repeat = Regex("(\\d+)\\s*[x×]\\s*(\\d+)\\s*m(?:nt|enit)", RegexOption.IGNORE_CASE).find(detail)
        if (repeat != null) {
            val count = repeat.groupValues[1]
            val minutes = repeat.groupValues[2]
            "$count x\n- $minutes m Z4 HR\n- 2m Z1 HR"
        } else {
            val minutes = Regex("(\\d+)\\s*m(?:nt|enit)", RegexOption.IGNORE_CASE).find(detail)?.groupValues?.getOrNull(1)
            if (minutes != null) {
                val zone = if (label.contains("pemanasan", true) || label.contains("pendinginan", true) || detail.contains("jalan", true)) "Z1 HR" else "Z2 HR"
                "- $minutes m $zone"
            } else null
        }
    }.joinToString("\n\n")
}

@Composable
private fun ApiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API key Intervals.icu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Masukkan API key pribadi dari pengaturan akun Intervals.icu. Kunci disimpan di penyimpanan privat aplikasi.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, label = { Text("API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (key.isNotBlank()) onSave(key.trim()) }) { Text("Simpan & sinkron") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

private fun strengthLabel(value: String) = when(value) { "hip" -> "Pinggul"; "calf" -> "Betis"; "leg" -> "Kaki"; "core" -> "Core"; "drill" -> "Drill"; else -> "Strength" }
private fun fmt(value: Double) = value.roundToInt().toString()
private fun pace(minutesPerKm: Double): String = RunSenseFormat.pace(minutesPerKm)
