package id.kamui.runsense

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joyodigitama.huaweiwear.HuaweiWatch
import com.joyodigitama.huaweiwear.WatchDevice
import com.joyodigitama.huaweiwear.WatchException
import com.joyodigitama.huaweiwear.WatchImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun linkLabel(link: String): String = when (link) {
    "disconnected" -> "Terputus"
    "connecting" -> "Menyambung…"
    "authenticating" -> "Memverifikasi…"
    "initializing" -> "Menyiapkan…"
    "connected" -> "Tersambung"
    "syncing" -> "Menyinkronkan…"
    else -> link
}

private fun supportLabel(s: String): String = when (s) {
    "tested" -> "teruji"
    "experimental" -> "eksperimental"
    "unsupported" -> "tidak didukung"
    else -> "belum dikenal"
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
private fun timeOf(sec: Long): String = TIME_FMT.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

@Composable
fun WatchScreen(modifier: Modifier = Modifier, onImported: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val watch = remember { HuaweiWatch.get(ctx) }
    val scope = rememberCoroutineScope()
    val state by watch.state.collectAsState()
    val found by watch.found.collectAsState()
    val scanning by watch.scanning.collectAsState()
    val log by watch.log.collectAsState()

    var message by remember { mutableStateOf("") }
    var bonded by remember { mutableStateOf<List<WatchDevice>>(emptyList()) }
    var missing by remember { mutableStateOf(HuaweiWatch.missingPermissions(ctx)) }
    var confirm by remember { mutableStateOf<WatchDevice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    fun loadBonded() {
        try { bonded = watch.bonded() } catch (e: WatchException) { message = e.message ?: e.code }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = HuaweiWatch.missingPermissions(ctx)
        if (missing.none { it.contains("BLUETOOTH") || it.contains("LOCATION") }) loadBonded()
    }
    LaunchedEffect(missing.isEmpty()) { if (missing.isEmpty()) loadBonded() }

    suspend fun importLocal(days: Int = 42): String {
        val result = withContext(Dispatchers.IO) { WatchImport.build(ctx, days) }
            ?: return "Database jam belum tersedia. Sinkronkan jam lebih dulu."
        onImported(result.json.toString())
        return "Diimpor: ${result.acts} aktivitas, ${result.wellnessDays} hari wellness."
    }

    fun connect(d: WatchDevice, confirmBind: Boolean) {
        scope.launch {
            busy = true
            message = "Menyambung ke ${d.name}…"
            try {
                watch.connect(d.address, confirmBind)
                message = "Tersambung ke ${d.name}."
            } catch (e: WatchException) {
                if (e.code == HuaweiWatch.ERR_BIND_CONFIRMATION) { confirm = d; message = "" }
                else message = "Gagal menyambung: ${e.message}"
            } catch (e: Exception) { message = "Gagal menyambung: ${e.message}" }
            finally { busy = false }
        }
    }

    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Pasangkan ${d.name} ke RunSense?") },
            text = { Text("Jam ini belum pernah diikat dengan HP ini. Ikatan pertama menggantikan pasangan HP-nya yang sekarang (mis. Huawei Health), jadi jam mungkin terlepas dari aplikasi itu. Konfirmasi juga akan diminta di layar jam.", fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { val x = d; confirm = null; connect(x, true) }) { Text("Lanjutkan") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } }
        )
    }

    val connected = state.link == "connected" || state.link == "syncing"
    val statusColor = when (state.link) {
        "connected" -> MaterialTheme.colorScheme.primary
        "syncing", "connecting", "authenticating", "initializing" -> androidx.compose.ui.graphics.Color(0xFFFFB300)
        else -> androidx.compose.ui.graphics.Color(0xFFFF5252)
    }
    val supportedBonded = bonded.filter { it.support != "unsupported" }
    val otherBonded = bonded.filter { it.support == "unsupported" }
    val supportedFound = found.filter { it.support != "unsupported" }
    val otherFound = found.filter { it.support == "unsupported" }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 5.dp)) {
            Text("PERANGKAT LATIHAN", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text("Your watch.", color = MaterialTheme.colorScheme.onBackground, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
            Text("Hubungkan jam, sinkronkan latihan, dan bawa data ke RunSense.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                    Text(linkLabel(state.link), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text("Perangkat: ${state.name ?: "Belum ada perangkat tersambung"}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                state.battery?.let { Text("Baterai $it%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                state.lastSyncSec?.let { Text("Sinkron terakhir ${timeOf(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                state.error?.let { Text("Kesalahan: $it", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            }
        }

        if (message.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
        }

        if (missing.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Izin Bluetooth diperlukan untuk mencari dan menyambung ke jam.", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    Button(onClick = { permLauncher.launch(missing.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) { Text("Beri izin") }
                }
            }
        } else {
            fun runOp(block: suspend () -> String) {
                scope.launch {
                    busy = true
                    message = "Menyinkronkan…"
                    try { message = block() } catch (e: Exception) { message = "Gagal sinkron: ${e.message}" }
                    finally { busy = false }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected && !busy, onClick = { runOp {
                        val r = watch.sync(); val imported = importLocal()
                        "Selesai: ${r.steps} langkah, ${r.workouts} latihan, ${r.sleep} data tidur, ${r.samples} sampel. $imported"
                    } }, modifier = Modifier.weight(1f)) { Text("Sinkron") }
                    OutlinedButton(enabled = connected && !busy, onClick = { runOp {
                        val r = watch.sync(days = 7); val imported = importLocal(7)
                        "Selesai (7 hari): ${r.steps} langkah, ${r.workouts} latihan, ${r.sleep} data tidur, ${r.samples} sampel. $imported"
                    } }, modifier = Modifier.weight(1f)) { Text("7 hari") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = connected && !busy, onClick = { runOp {
                        val r = watch.syncHrv(); val imported = importLocal()
                        "HRV: ${r.stored} malam tersimpan (${r.result}). $imported"
                    } }, modifier = Modifier.weight(1f)) { Text("HRV") }
                    OutlinedButton(enabled = connected && !busy, onClick = { watch.disconnect() }, modifier = Modifier.weight(1f)) { Text("Putuskan") }
                }
            }

            Text("Jam di HP ini", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (supportedBonded.isEmpty()) Text("Belum ada jam yang didukung terpasang.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            supportedBonded.forEach { d -> DeviceRow(d, busy) { connect(d, false) } }
            if (otherBonded.isNotEmpty()) DisclosureSection("Perangkat lain", "${otherBonded.size} perangkat tidak didukung") { otherBonded.forEach { d -> DeviceRow(d, busy) { } } }
            OutlinedButton(onClick = { loadBonded() }, modifier = Modifier.fillMaxWidth()) { Text("Segarkan daftar") }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cari di sekitar", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { try { if (scanning) watch.stopScan() else watch.startScan() } catch (e: WatchException) { message = e.message ?: e.code } }) { Text(if (scanning) "Berhenti mencari" else "Cari jam", color = MaterialTheme.colorScheme.primary) }
            }
            supportedFound.forEach { d -> DeviceRow(d, busy) { watch.stopScan(); connect(d, false) } }
            if (otherFound.isNotEmpty()) DisclosureSection("Perangkat lain di sekitar", "${otherFound.size} perangkat tidak didukung") { otherFound.forEach { d -> DeviceRow(d, busy) { } } }
        }

        DisclosureSection("Log sinkronisasi", "Riwayat teknis perangkat") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Sembunyikan log" else "Tampilkan log", color = MaterialTheme.colorScheme.primary) }
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("log jam", log.joinToString("\n")))
                    message = "Log disalin."
                }) { Text("Salin log", color = MaterialTheme.colorScheme.primary) }
            }
            if (showLog) Text(log.takeLast(60).joinToString("\n").ifBlank { "(log kosong)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRow(d: WatchDevice, busy: Boolean, onConnect: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(d.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(d.model, supportLabel(d.support), if (d.remembered) "diingat" else null).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Button(enabled = !busy && d.support != "unsupported", onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text("Sambungkan") }
        }
    }
}