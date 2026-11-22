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
internal fun HomeScreen(activities: List<ActivityRecord>, summary: TrainingSummary, domainResult: com.runnow.domain.ProcessResult?, snapshot: Snapshot?, planSettings: com.runnow.domain.Settings, weather: WeatherSnapshot?, weatherMessage: String, lastSyncAt: Long, modifier: Modifier, onAdd: () -> Unit, onStrength: () -> Unit, onRefreshWeather: () -> Unit, onWorkout: () -> Unit, onViewAll: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { HomePageHero() }
        item {
            val syncLabel = if (lastSyncAt > 0L) SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("id", "ID")).format(Date(lastSyncAt)) else "Belum pernah sinkron"
            Text("Intervals.icu · Sinkron terakhir: " + syncLabel, color = Muted, fontSize = 11.sp)
        }
        item { HomeWeatherSummary(weather, onRefreshWeather) }
        item { CoachRecommendationCard(domainResult?.recommendation, onWorkout) }
        item { ReadinessCard(summary.readiness ?: domainResult?.athlete?.score, summary.recommendation) }
        item { WeeklyPlanHomeCard(snapshot, planSettings) }
        item { SectionKicker("LATIHANMU", "01") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("BEBAN 7 HARI", fmt(summary.acuteLoad), Modifier.weight(1f))
            MetricCard("ACWR", summary.acwr?.let { RunSenseFormat.decimal(it, 2) } ?: "—", Modifier.weight(1f))
            MetricCard("STRENGTH 48J", RunSenseFormat.decimal(summary.strengthLoad48h, 1), Modifier.weight(1f))
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = OnLime)) { Text("＋ Catat latihan") }
            OutlinedButton(onClick = onStrength, modifier = Modifier.weight(1f)) { Text("＋ Strength") }
        } }
        item { SectionKicker("AKTIVITAS TERBARU", "02") }
        item { SectionTitle("Lari terakhir", "SESI TERBARU") }
        item {
            val latest = activities.firstOrNull { it.type.equals("run", true) }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (latest == null) EmptyCard("Belum ada lari tersimpan. Tambahkan sesi pertama untuk mulai membangun analisis beban.")
                else ActivityCard(latest, onClick = onViewAll)
                TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) { Text("Lihat semua riwayat", color = Lime) }
            }
        }
    }
}

