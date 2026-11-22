package id.kamui.runsense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.kamui.runsense.domain.*

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
        PageHero("ATHLETE SETTINGS", "Your", "profile.", "Data tubuh, target, jadwal, dan koneksi.")

        // ---- Data tubuh & detak
        SettingsGroup("Data tubuh & detak") {
            SettingRow(
                "Detak jantung",
                "RHR ${profile.restingHr} · HRmax ${profile.maxHr} · LTHR ${profile.thresholdHr}",
                "Ubah", onProfile, Lime
            )
            GroupDivider()
            SettingRow(
                "Tinggi & berat badan",
                "Usia ${if (profile.ageYears > 0) "${profile.ageYears} tahun" else "belum diisi"} · TB ${if (profile.heightCm > 0) "${profile.heightCm} cm" else "belum diisi"} · BB ${if (profile.weightKg > 0) RunSenseFormat.kg(profile.weightKg) else "belum diisi"}",
                "Ubah", onProfile, Lime
            )
        }
        Text("Data otomatis diambil dari Intervals.icu jika tersedia. Isi nilai manual hanya bila ingin menimpa hasil otomatis.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth())

        // ---- Target & gaya coaching
        SettingsGroup("Target & gaya coaching") {
            GroupBlock {
                Text("Gaya coaching", color = White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("conservative" to "Konservatif", "moderate" to "Moderat", "aggressive" to "Agresif").forEach { item ->
                        RunChip(item.second, coachingStyle == item.first, { onStyle(item.first) })
                    }
                }
                Text(
                    when (coachingStyle) {
                        "conservative" -> "Lebih banyak recovery."
                        "aggressive" -> "Lebih banyak sesi kualitas."
                        else -> "Seimbang (disarankan)."
                    },
                    color = Muted, fontSize = 11.sp
                )
            }
            GroupDivider()
            SettingRow("Target strength", "$strengthTarget hari per minggu", "Atur", onStrengthTarget, Lime)
        }

        // ---- Jadwal latihan & lomba
        SettingsGroup("Jadwal latihan & lomba") {
            SettingRow("Rencana mingguan & race", "Jenis latihan, jarak harian, tanggal lomba, dan target waktu.", "Atur", onPlanRace, Lime)
        }

        // ---- Pola lari-jalan
        SettingsGroup("Pola lari–jalan") {
            GroupBlock { RunWalkSettingsCard(runWalk, onRunWalk) }
        }

        // ---- Koneksi data
        SettingsGroup("Koneksi data") {
            SettingRow(
                "Intervals.icu",
                if (hasIntervalsKey) "API key tersimpan aman · ••••" + (apiKeyLastFour ?: "••••") else "Masukkan API key untuk mengambil riwayat aktivitas dan wellness.",
                if (hasIntervalsKey) "Terhubung" else "Belum terhubung",
                null,
                if (hasIntervalsKey) Lime else Muted
            )
            GroupDivider()
            GroupBlock {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RunButton(if (hasIntervalsKey) "Ganti API key" else "Masukkan API key", onApiKey, Modifier.weight(1f), primary = !hasIntervalsKey)
                    RunButton(if (syncBusy) "Menyinkronkan…" else "Sinkron ulang", onSync, Modifier.weight(1f), enabled = hasIntervalsKey && !syncBusy)
                }
                if (syncMessage.isNotBlank()) Text(syncMessage, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            GroupDivider()
            SettingRow("Coach", "Kondisi hari ini, kapasitas, beban, dan rencana vs realisasi.", null, onCoach)
            GroupDivider()
            SettingRow("Jam & koneksi", "Pasangkan jam Huawei dan impor data latihan.", null, onWatch)
        }

        // ---- Tampilan
        SettingsGroup("Tampilan") {
            GroupBlock {
                Text("Tema aplikasi", color = White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RunChip("Gelap", !lightTheme, { onTheme(false) })
                    RunChip("Terang", lightTheme, { onTheme(true) })
                }
            }
        }

        // ---- Skrining
        val yesCount = parqAnswers.count { it == true }
        val answered = parqAnswers.count { it != null }
        SettingsGroup("Skrining aktivitas") {
            SettingRow(
                "PAR-Q · 7 pertanyaan",
                if (answered < 7) "$answered/7 pertanyaan terisi" else if (yesCount > 0) "$yesCount jawaban YA — perlu tindak lanjut" else "Semua jawaban TIDAK",
                if (yesCount > 0) "Perhatian" else "Isi",
                onParq,
                if (yesCount > 0) Color(0xFFFFC36A) else Lime
            )
        }
        if (yesCount > 0) Text("Ada jawaban YA. Konsultasikan dengan tenaga kesehatan sebelum memulai atau meningkatkan latihan.", color = Color(0xFFFFC36A), fontSize = 11.sp, lineHeight = 16.sp)

        // ---- Wellness
        var showAllWellness by rememberSaveable(wellness) { mutableStateOf(false) }
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
        SettingsGroup("Wellness · ${validWellness.size} hari valid") {
            if (wellnessRows.isEmpty()) {
                SettingRow("Belum ada data", "Belum ada data wellness valid dalam 3 hari terakhir.")
            }
            wellnessRows.forEachIndexed { index, item ->
                if (index > 0) GroupDivider()
                SettingRow(
                    RunSenseFormat.strengthDate(item.date),
                    "RHR ${RunSenseFormat.bpm(item.restingHr)} bpm · tidur ${RunSenseFormat.sleepHours(item.sleepHours)} · HRV ${RunSenseFormat.integer(item.hrv)}"
                )
            }
            if (validWellness.size > recentWellness.size) {
                GroupDivider()
                TextButton(onClick = { showAllWellness = !showAllWellness }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAllWellness) "Tampilkan 3 hari terakhir" else "Lihat semua (${validWellness.size} hari)", color = Lime)
                }
            }
            GroupDivider()
            GroupBlock { RunButton("＋ Catat wellness hari ini", onWellness, Modifier.fillMaxWidth()) }
        }

        // ---- Backup
        SettingsGroup("Cadangkan data") {
            GroupBlock {
                Text("Ekspor aktivitas, strength, wellness, profil, rencana, PAR-Q, dan pengaturan. Pemulihan menggabungkan data baru tanpa menimpa catatan yang sudah ada. API key tidak ikut diekspor.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RunButton("Ekspor backup", onBackupExport, Modifier.weight(1f))
                    RunButton("Pulihkan backup", onBackupImport, Modifier.weight(1f))
                }
            }
        }

        Text("Data aktivitas dan wellness dari Huawei serta Intervals digabung ke riwayat lokal. API key disimpan di penyimpanan privat aplikasi.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
    }
}
