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
internal fun SettingsScreen(
    profile: AthleteProfile, wellness: List<WellnessRecord>, modifier: Modifier,
    strengthTarget: Int, parqAnswers: List<Boolean?>, onStrengthTarget: () -> Unit, onParq: () -> Unit,
    hasIntervalsKey: Boolean, apiKeyLastFour: String?, syncBusy: Boolean, syncMessage: String,
    onProfile: () -> Unit, onWellness: () -> Unit, onPlanRace: () -> Unit, onApiKey: () -> Unit,
    onBackupExport: () -> Unit, onBackupImport: () -> Unit, onSync: () -> Unit,
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
        Text(if (hasIntervalsKey) "API key tersimpan aman · ••••" + (apiKeyLastFour ?: "••••") + ". Sinkronkan aktivitas dan wellness dari akun Intervals.icu." else "Masukkan API key Intervals.icu untuk mengambil riwayat aktivitas dan wellness.", fontSize = 11.sp, lineHeight = 16.sp)
        Button(onClick = onApiKey, colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Lime)) { Text(if (hasIntervalsKey) "Ganti API key" else "Masukkan API key") }
        OutlinedButton(onClick = onSync, enabled = hasIntervalsKey && !syncBusy) { Text(if (syncBusy) "Menyinkronkan…" else "Sinkronkan sekarang") }
        if (syncMessage.isNotBlank()) Text(syncMessage, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        SectionTitle("Cadangkan data", "BACKUP JSON · SAF")
        Text("Ekspor aktivitas, strength, wellness, profil, rencana, PAR-Q, dan pengaturan. Pemulihan menggabungkan data baru tanpa menimpa catatan yang sudah ada. API key tidak ikut diekspor.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBackupExport, modifier = Modifier.weight(1f)) { Text("Ekspor backup") }
            OutlinedButton(onClick = onBackupImport, modifier = Modifier.weight(1f)) { Text("Pulihkan backup") }
        }
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
        if (validWellness.size > recentWellness.size) {
            TextButton(onClick = { showAllWellness = !showAllWellness }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAllWellness) "Tampilkan 3 hari terakhir" else "Lihat semua (${validWellness.size} hari)", color = Lime)
            }
        }
        OutlinedButton(onClick = onWellness) { Text("＋ Catat wellness hari ini") }
        Text("Data aktivitas dan wellness dari Huawei serta Intervals digabung ke riwayat lokal. API key disimpan di penyimpanan privat aplikasi.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

