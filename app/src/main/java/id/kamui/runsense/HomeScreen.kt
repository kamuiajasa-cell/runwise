package id.kamui.runsense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.kamui.runsense.domain.*
import com.runnow.domain.Snapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HomeScreen(
    activities: List<ActivityRecord>,
    summary: TrainingSummary,
    domainResult: com.runnow.domain.ProcessResult?,
    snapshot: Snapshot?,
    planSettings: com.runnow.domain.Settings,
    weather: WeatherSnapshot?,
    weatherMessage: String,
    lastSyncAt: Long,
    modifier: Modifier,
    onAdd: () -> Unit,
    onStrength: () -> Unit,
    onRefreshWeather: () -> Unit,
    onWorkout: () -> Unit,
    onViewAll: () -> Unit
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { HomePageHero() }
        item { SectionKicker("LATIHANMU", "01") }
        item {
            val recommendation = domainResult?.recommendation
            if (recommendation == null) {
                EmptyCard("Rekomendasi Coach belum tersedia. Hubungkan Intervals.icu di tab Saya atau catat latihan pertamamu.")
            } else {
                CoachRecommendationCard(recommendation, onWorkout)
            }
        }
        item { ReadinessCard(summary.readiness ?: domainResult?.athlete?.score, summary.recommendation) }
        item { WeeklyPlanHomeCard(snapshot, planSettings) }
        item { WeatherCard(weather, weatherMessage, onRefreshWeather) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("BEBAN 7 HARI", fmt(summary.acuteLoad), Modifier.weight(1f))
                MetricCard("ACWR", summary.acwr?.let { RunSenseFormat.decimal(it, 2) } ?: "—", Modifier.weight(1f))
                MetricCard("STRENGTH 48J", RunSenseFormat.decimal(summary.strengthLoad48h, 1), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RunButton("＋ Catat latihan", onAdd, Modifier.weight(1f), primary = true)
                RunButton("＋ Strength", onStrength, Modifier.weight(1f))
            }
        }
        item { SectionKicker("AKTIVITAS TERBARU", "02") }
        item {
            val latest = activities.firstOrNull { it.type.equals("run", true) }
            if (latest == null) {
                EmptyCard("Belum ada lari tersimpan. Tambahkan sesi pertama untuk mulai membangun analisis beban.")
            } else {
                ActivityCard(latest, onClick = onViewAll)
            }
        }
        item {
            TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) { Text("Lihat semua riwayat", color = Lime, fontSize = 13.sp) }
        }
        item {
            val syncLabel = if (lastSyncAt > 0L) SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("id", "ID")).format(Date(lastSyncAt)) else "Belum sinkron"
            SyncBar("Intervals.icu · " + syncLabel, lastSyncAt > 0L)
        }
    }
}
