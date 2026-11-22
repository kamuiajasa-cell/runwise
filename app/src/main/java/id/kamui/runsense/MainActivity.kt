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

internal var Ink by mutableStateOf(Color(0xFF0A0A0A))
internal var Panel by mutableStateOf(Color(0xFF1E1E1E))
internal var Lime by mutableStateOf(Color(0xFFC6FF38))
internal var OnLime by mutableStateOf(Color(0xFF0A0A0A))
internal var Muted by mutableStateOf(Color(0xFF9E9E9E))
internal var White by mutableStateOf(Color(0xFFFAFAFA))

internal fun applyRunSenseTheme(light: Boolean) {
    Ink = if (light) Color(0xFFF1F3ED) else Color(0xFF0A0A0A)
    Panel = if (light) Color(0xFFFFFFFF) else Color(0xFF1E1E1E)
    Lime = if (light) Color(0xFF3A6E0C) else Color(0xFFC6FF38)
    OnLime = if (light) Color.White else Color(0xFF0A0A0A)
    Muted = if (light) Color(0xFF62695D) else Color(0xFF9E9E9E)
    White = if (light) Color(0xFF171B14) else Color(0xFFFAFAFA)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(10, 10, 10)
        window.navigationBarColor = android.graphics.Color.rgb(10, 10, 10)
        val repository = RunSenseRepository(applicationContext)
        val intervals = NativeIntervalsRuntime(applicationContext)
        setContent { RunSenseNativeApp(repository, intervals) }
    }
}

private data class NativeDashboard(
    val profile: AthleteProfile,
    val activities: List<ActivityRecord>,
    val strength: List<StrengthRecord>,
    val wellness: List<WellnessRecord>,
    val runWalk: RunWalkSettings,
    val planSettings: com.runnow.domain.Settings,
    val strengthTarget: Int,
    val parqAnswers: List<Boolean?>,
    val weather: WeatherSnapshot?,
    val snapshot: Snapshot?,
    val summary: TrainingSummary,
    val analysis: com.runnow.domain.ProcessResult?,
    val onboardingComplete: Boolean,
    val hasIntervalsKey: Boolean,
    val apiKeyLastFour: String?,
    val lastSyncAt: Long
)

private enum class RunTab(val label: String, val mark: String) {
    HOME("Beranda", "⌂"), TRAINING("Riwayat", "◷"), PROGRESS("Progress", "↗"),
    PROFILE("Saya", "◎"), COACH("Coach", "✦"), WATCH("Jam", "⌚")
}

private val primaryTabs = listOf(RunTab.HOME, RunTab.TRAINING, RunTab.PROGRESS, RunTab.PROFILE)

@Composable
internal fun RunSenseNativeApp(repository: RunSenseRepository, intervals: NativeIntervalsRuntime) {
    var selected by rememberSaveable { mutableStateOf(RunTab.HOME) }
    var lightTheme by remember { mutableStateOf(repository.isLightTheme()) }
    var onboarding by remember { mutableStateOf(false) }
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
    var autoSyncStarted by rememberSaveable { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    BackHandler(enabled = selected != RunTab.HOME || showActivity || showStrength || showStrengthTarget || showParq || showWellness || showProfile || showApiKey || showPlanRace || showWorkoutComposer || checkinActivity != null) {
        when {
            checkinActivity != null -> checkinActivity = null
            showWorkoutComposer -> showWorkoutComposer = false
            showActivity -> showActivity = false
            showStrength -> showStrength = false
            showStrengthTarget -> showStrengthTarget = false
            showParq -> showParq = false
            showWellness -> showWellness = false
            showProfile -> showProfile = false
            showApiKey -> showApiKey = false
            showPlanRace -> showPlanRace = false
            selected != RunTab.HOME -> selected = RunTab.HOME
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(repository.exportBackupJson().toByteArray(Charsets.UTF_8))
                    } ?: error("File backup tidak dapat dibuat.")
                }
            }.onSuccess { syncMessage = "Backup RunSense berhasil disimpan." }
                .onFailure { syncMessage = "Backup gagal: ${it.message ?: "kesalahan penyimpanan"}" }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("File backup tidak dapat dibaca.")
                    repository.importBackupJson(raw)
                }
            }.onSuccess { result ->
                revision++
                syncMessage = "Pulihkan backup: ${result.activitiesAdded} aktivitas, ${result.strengthAdded} strength, ${result.wellnessAdded} wellness, ${result.settingsAdded} pengaturan ditambahkan; ${result.skipped} dilewati (data lama dipertahankan)."
            }.onFailure { syncMessage = "Pemulihan gagal: ${it.message ?: "file tidak valid"}" }
        }
    }
    val dashboard by produceState<NativeDashboard?>(initialValue = null, key1 = revision) {
        value = withContext(Dispatchers.Default) {
            val profile = repository.profile()
            val activities = repository.activities()
            val strength = repository.strength()
            val wellness = repository.wellness()
            val runWalk = repository.runWalkSettings()
            val planSettings = repository.planSettings()
            val weather = repository.currentWeather()
            val snapshot = runCatching { intervals.snapshot() }.getOrNull()
            val summary = TrainingEngine.summarize(activities, strength, wellness, profile)
            val analysis = runCatching {
                nativeRunSenseAnalysis(activities, strength, wellness, profile, snapshot, planSettings, runWalk, (weather?.temperatureC ?: 0.0) >= 30.0)
            }.getOrNull()
            NativeDashboard(profile, activities, strength, wellness, runWalk, planSettings,
                repository.weeklyStrengthTarget(), repository.parqAnswers(), weather, snapshot,
                summary, analysis, repository.onboardingComplete(), intervals.hasApiKey(), intervals.apiKeyLastFour(), repository.lastSyncAt())
        }
    }
    val profile = dashboard?.profile ?: AthleteProfile()
    val activities = dashboard?.activities.orEmpty()
    val strength = dashboard?.strength.orEmpty()
    val wellness = dashboard?.wellness.orEmpty()
    val runWalk = dashboard?.runWalk ?: RunWalkSettings()
    val planSettings = dashboard?.planSettings ?: com.runnow.domain.Settings()
    val strengthTarget = dashboard?.strengthTarget ?: 2
    val parqAnswers = dashboard?.parqAnswers ?: List(7) { null }
    val weather = dashboard?.weather
    val baseSnapshot = dashboard?.snapshot
    val summary = dashboard?.summary ?: TrainingEngine.summarize(emptyList(), emptyList(), emptyList(), profile)
    val domainResult = dashboard?.analysis
    val hasIntervalsKey = dashboard?.hasIntervalsKey == true
    LaunchedEffect(dashboard) {
        val data = dashboard ?: return@LaunchedEffect
        onboarding = !data.onboardingComplete && !data.hasIntervalsKey && data.activities.isEmpty() && data.wellness.isEmpty()
    }
    LaunchedEffect(dashboard?.hasIntervalsKey) {
        val data = dashboard ?: return@LaunchedEffect
        if (data.hasIntervalsKey && !autoSyncStarted &&
            (data.lastSyncAt == 0L || System.currentTimeMillis() - data.lastSyncAt > 2 * 60 * 60 * 1000L)) {
            autoSyncStarted = true
            syncBusy = true
            syncMessage = "Sinkron otomatis Intervals.icu…"
            try {
                syncMessage = withContext(Dispatchers.IO) { intervals.sync() }
                revision++
            } catch (e: Exception) {
                syncMessage = "Sinkron otomatis belum berhasil. Periksa koneksi lalu coba lagi."
            } finally { syncBusy = false }
        }
    }
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
                    weatherMessage = "Cuaca belum dapat diperbarui. Pastikan izin lokasi aktif dan koneksi internet tersedia."
                }
            }
        } else weatherMessage = "Izin lokasi ditolak. Cuaca belum dapat diperbarui."
    }
    fun refreshWeather() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch {
                weatherMessage = "Memuat cuaca dari lokasi perangkat…"
                try {
                    withContext(Dispatchers.IO) { repository.fetchCurrentWeather() }
                    weatherMessage = "Cuaca diperbarui dari Open-Meteo."
                    revision++
                } catch (e: Exception) {
                    weatherMessage = "Cuaca belum dapat diperbarui. Pastikan izin lokasi aktif dan koneksi internet tersedia."
                }
            }
        } else weatherPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) {
        val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (permitted && (weather == null || System.currentTimeMillis() - weather.fetchedAt > 6 * 60 * 60 * 1000L)) refreshWeather()
    }
    fun changed() { revision++ }
    SideEffect {
        applyRunSenseTheme(lightTheme)
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = lightTheme
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = lightTheme
        }
    }

    MaterialTheme(colorScheme = if (lightTheme) lightColorScheme(primary = Lime, background = Ink, surface = Panel, onPrimary = OnLime, onBackground = White, onSurface = White) else darkColorScheme(primary = Lime, background = Ink, surface = Panel, onPrimary = OnLime, onBackground = White, onSurface = White)) {
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
            },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { padding ->
            when (selected) {
                RunTab.HOME -> HomeScreen(activities, summary, domainResult, baseSnapshot, planSettings, weather, weatherMessage, dashboard?.lastSyncAt ?: 0L, Modifier.padding(padding), onAdd = { showActivity = true }, onStrength = { showStrength = true }, onRefreshWeather = { refreshWeather() }, onWorkout = { showWorkoutComposer = true }, onViewAll = { selected = RunTab.TRAINING })
                RunTab.TRAINING -> TrainingScreen(
                    activities, strength, profile, domainResult, baseSnapshot, repository, Modifier.padding(padding),
                    isRefreshing = syncBusy,
                    onRefresh = {
                        if (!syncBusy) scope.launch {
                            if (!hasIntervalsKey) { syncMessage = "Hubungkan Intervals.icu untuk memperbarui riwayat."; return@launch }
                            syncBusy = true
                            try {
                                syncMessage = withContext(Dispatchers.IO) { intervals.sync() }
                                changed()
                            } catch (e: Exception) {
                                syncMessage = "Riwayat belum berhasil diperbarui. Periksa koneksi dan coba lagi."
                            } finally { syncBusy = false }
                        }
                    },
                    onAdd = { showActivity = true }, onStrength = { showStrength = true }, onCheckin = { checkinActivity = it }
                )
                RunTab.PROGRESS -> ProgressScreen(
                    activities, summary, domainResult, baseSnapshot, repository, strength, profile, Modifier.padding(padding),
                    onCheckin = { checkinActivity = it }
                )
                RunTab.COACH -> CoachScreen(activities, summary, strength, strengthTarget, parqAnswers, domainResult, baseSnapshot, planSettings, weather, weatherMessage, Modifier.padding(padding), onRefreshWeather = { refreshWeather() }, onWorkout = { showWorkoutComposer = true })
                RunTab.WATCH -> WatchScreen(Modifier.padding(padding)) { json ->
                    if (!syncBusy) scope.launch {
                        syncBusy = true
                        syncMessage = "Mengimpor data Huawei…"
                        try {
                            withContext(Dispatchers.IO) { repository.importWatchJson(json) }
                            withContext(Dispatchers.Default) { intervals.ingestWatchJson(json) }
                            changed()
                            syncMessage = "Data Huawei berhasil digabung ke riwayat lokal."
                        } catch (e: Exception) {
                            syncMessage = "Impor Huawei belum berhasil. Periksa file ekspor dan coba lagi."
                        } finally { syncBusy = false }
                    }
                }
                RunTab.PROFILE -> SettingsScreen(
                    profile, wellness, Modifier.padding(padding),
                    onCoach = { selected = RunTab.COACH }, onWatch = { selected = RunTab.WATCH },
                    runWalk = runWalk, lightTheme = lightTheme, coachingStyle = planSettings.style,
                    onRunWalk = { repository.saveRunWalkSettings(it); changed() },
                    onTheme = { lightTheme = it; repository.saveLightTheme(it) },
                    onStyle = { style -> repository.savePlanSettings(planSettings.copy(style = style)); changed() },
                    hasIntervalsKey = hasIntervalsKey, apiKeyLastFour = dashboard?.apiKeyLastFour, syncBusy = syncBusy, syncMessage = syncMessage,
                    onProfile = { showProfile = true }, onWellness = { showWellness = true }, onPlanRace = { showPlanRace = true },
                    strengthTarget = strengthTarget, parqAnswers = parqAnswers,
                    onStrengthTarget = { showStrengthTarget = true }, onParq = { showParq = true },
                    onApiKey = { showApiKey = true },
                    onBackupExport = { exportBackup.launch("RunSense-backup-${java.time.LocalDate.now()}.json") },
                    onBackupImport = { importBackup.launch(arrayOf("application/json", "text/json", "*/*")) },
                    onSync = {
                        scope.launch {
                            syncBusy = true
                            syncMessage = "Menyinkronkan Intervals.icu…"
                            try {
                                syncMessage = withContext(Dispatchers.IO) { intervals.sync() }
                                changed()
                            } catch (e: Exception) {
                                syncMessage = "Sinkronisasi belum berhasil. Periksa API key dan koneksi internet, lalu coba lagi."
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
                            syncMessage = "Workout belum berhasil dikirim. Periksa koneksi dan API key Intervals.icu."
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
                    syncMessage = "Sinkronisasi belum berhasil. Periksa API key dan koneksi internet, lalu coba lagi."
                } finally { syncBusy = false }
            }
        })
    }
}

@Composable
internal fun OnboardingScreen(status: String, onConnect: () -> Unit, onSkip: () -> Unit) {
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
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = OnLime)) { Text("Hubungkan & Mulai") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Lanjut tanpa sinkron") }
                if (status.isNotBlank()) Text(status, color = Color(0xFFFFC36A), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
internal fun RunWalkSettingsCard(value: RunWalkSettings, onSave: (RunWalkSettings) -> Unit) {
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
internal fun Page(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun WeeklyPlanHomeCard(snapshot: Snapshot?, settings: com.runnow.domain.Settings) {
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
                            Text(if (plan == null || RunSenseFormat.valid(plan.km) == null) " " else RunSenseFormat.decimal(plan.km, 1), color = Muted, fontSize = 11.sp)
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
internal fun ProgressDimensionCard(
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
internal fun WeeklyDistanceChart(activities: List<ActivityRecord>) {
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
internal fun TrainingTrendChart(activities: List<ActivityRecord>) {
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
internal fun HeartRateZoneChart(activities: List<ActivityRecord>, snapshot: Snapshot?) {
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
internal fun SessionReviewCard(item: ActivityItem, snapshot: Snapshot?, repository: RunSenseRepository) {
    val review = remember(item.act, snapshot) { snapshot?.reviewRun(item.act) }
    val streamKey = item.act.icuId ?: item.act.lid
    val rawPoints by produceState<List<RawStreamPoint>>(initialValue = emptyList(), key1 = streamKey, key2 = snapshot) {
        value = withContext(Dispatchers.IO) { repository.streamPoints(streamKey) }
    }
    val cadence by produceState(initialValue = CadenceAnalyzer.analyze(emptyList(), 5), key1 = rawPoints) {
        value = withContext(Dispatchers.Default) { CadenceAnalyzer.analyze(rawPoints, 5) }
    }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(item.why.ifBlank { "Analisa sesi tersedia di bawah." }, color = White, fontSize = 12.sp, lineHeight = 17.sp)
            review?.sections?.take(3)?.forEachIndexed { index, section ->
                DisclosureSection(section.title, initiallyExpanded = index == 0) {
                    section.notes.take(3).forEach { note ->
                        Text("• ${note.text}", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            DisclosureSection(
                "Kadens per 5 detik",
                if (rawPoints.isNotEmpty()) "${cadence.windows.size} segmen · ${cadence.runningWindows} lari · ${cadence.walkingWindows} jalan" else "Stream sesi belum tersedia"
            ) {
                if (rawPoints.isNotEmpty()) {
                    cadence.windows.forEach { w ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(CadenceAnalyzer.formatTime(w.startSec), color = Muted, fontSize = 11.sp)
                            Text(w.cadenceSpm?.let { "${it.roundToInt()} spm" } ?: "—", color = White, fontSize = 11.sp)
                            Text(w.mode, color = if (w.mode == "Lari") Lime else Muted, fontSize = 11.sp)
                            Text(CadenceAnalyzer.formatPace(w.paceSecPerKm), color = Muted, fontSize = 11.sp)
                        }
                    }
                } else {
                    Text("Data stream sesi belum tersedia. Coba sinkronkan ulang jika sesi ini belum memiliki stream.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}
@Composable
internal fun WeeklyReviewCard(review: WeeklyReview) {
    val ratio = if (review.targetKm > 0) (review.actualKm / review.targetKm).coerceIn(0.0, 1.0).toFloat() else 0f
    Surface(color = Panel, shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("RINGKASAN PELAKSANAAN", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(RunSenseFormat.distanceKm(review.actualKm, aggregate = true), color = Lime, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text("realisasi hingga hari ini", color = Muted, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(RunSenseFormat.distanceKm(review.targetKm, aggregate = true), color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
                Text(RunSenseFormat.distanceKm(review.nextWeekTargetKm, aggregate = true), color = Lime, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (review.extraKm > 0.2) {
                Text("Ada ${RunSenseFormat.distanceKm(review.extraKm, aggregate = true)} di luar target harian. Pastikan penambahan spontan ini tidak menumpuk dengan sesi berat.", color = Color(0xFFFFC16B), fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
internal fun HomePageHero() {
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
            Text("RINGKASAN LATIHAN HARI INI", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Surface(color = Lime, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("R", color = OnLime, fontSize = 20.sp, fontWeight = FontWeight.Black) }
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
internal fun PageHero(eyebrow: String, title: String, accent: String, subtitle: String) {
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
internal fun SectionKicker(label: String, index: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.35.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = .10f)))
        Text(index, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun HomeWeatherSummary(weather: WeatherSnapshot?, onRefresh: () -> Unit) {
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
internal fun WeatherCard(weather: WeatherSnapshot?, message: String, onRefresh: () -> Unit) {
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
internal fun CoachRecommendationCard(recommendation: com.runnow.domain.Recommendation?, onWorkout: () -> Unit) {
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
internal fun ReadinessCard(score: Int?, recommendation: String) {
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
internal fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = modifier.border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(16.dp))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun SectionTitle(title: String, action: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(action, color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun CheckinActivityCard(activity: ActivityRecord, onCheckin: (ActivityRecord) -> Unit) {
    val stamp = SimpleDateFormat("dd MMM · HH:mm", Locale("id", "ID")).format(Date(activity.startedAt))
    Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = .05f), RoundedCornerShape(20.dp))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Lari · $stamp", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${RunSenseFormat.durationMinutes(activity.durationMin)} · ${RunSenseFormat.distanceKm(activity.distanceKm)} · HR ${RunSenseFormat.bpm(activity.averageHr)}", color = Muted, fontSize = 11.sp)
                }
                Text(if (activity.quality) "QUALITY" else "LARI", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
internal fun ActivityCard(a: ActivityRecord, profile: AthleteProfile = AthleteProfile(), onClick: () -> Unit = {}) {
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
internal fun InfoCard(title: String, detail: String, tag: String) {
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
internal fun EmptyCard(text: String) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun ActivityDialog(onDismiss: () -> Unit, onSave: (String, Double, Double, Int, Double, Boolean, Int, String, Int) -> Unit) {
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
internal fun SessionCheckinDialog(
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
internal fun StrengthDialog(onDismiss: () -> Unit, onSave: (String, Int, Int, Boolean, String, Int, Int, Double) -> Unit) {
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
internal fun StrengthTargetDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
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
internal fun ParqDialog(current: List<Boolean?>, onDismiss: () -> Unit, onSave: (List<Boolean?>) -> Unit) {
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
internal fun PlanRaceDialog(
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
internal fun WellnessDialog(onDismiss: () -> Unit, onSave: (Int, Double, Double) -> Unit) {
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
internal fun ProfileDialog(profile: AthleteProfile, onDismiss: () -> Unit, onSave: (AthleteProfile) -> Unit) {
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
internal fun WorkoutComposerDialog(
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

internal fun recommendationWorkoutText(recommendation: com.runnow.domain.Recommendation): String {
    recommendation.wl?.takeIf { it.isNotEmpty() }?.let { return it.joinToString("\n").trim() }
    return recommendation.steps.mapNotNull { (label, detail) ->
        val repeat = Regex("(\\d+)\\s*[x×]\\s*(\\d+)\\s*m(?:nt|enit)", RegexOption.IGNORE_CASE).find(detail)
        if (repeat != null) {
            val count = repeat.groupValues[1]
            val minutes = repeat.groupValues[2]
            "${count}x\n- $minutes m Z4 HR\n- 2m Z1 HR"
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
internal fun ApiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
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
internal fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

internal fun strengthLabel(value: String) = when(value) { "hip" -> "Pinggul"; "calf" -> "Betis"; "leg" -> "Kaki"; "core" -> "Core"; "drill" -> "Drill"; else -> "Strength" }
internal fun fmt(value: Double) = RunSenseFormat.integer(value)
internal fun pace(minutesPerKm: Double): String = RunSenseFormat.pace(minutesPerKm)
