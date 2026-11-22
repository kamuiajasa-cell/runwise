package id.kamui.runsense.data

import android.content.Context
import android.location.LocationManager
import com.runnow.data.Clock
import com.runnow.data.Codec
import com.runnow.data.snapshot
import com.runnow.domain.Snapshot
import com.runnow.data.HttpClient
import com.runnow.data.HttpResponse
import com.runnow.data.IcuHttp
import com.runnow.data.KeyValueStore
import com.runnow.data.RunSenseRepository as DomainRepository
import com.runnow.data.WeatherApi
import com.runnow.data.OpenMeteoHttp
import com.runnow.data.Locator
import com.runnow.domain.GeoLoc
import com.runnow.domain.ClimCtx
import com.runnow.domain.ClimPers
import com.runnow.domain.ClimProfile
import com.runnow.domain.ClimSlot
import com.runnow.domain.clPers
import com.runnow.domain.clProfile
import com.runnow.domain.clSlots
import com.runnow.domain.climDate
import com.runnow.domain.clK
import com.runnow.domain.stage
import com.runnow.domain.detectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.time.Instant

/** Climate-aware Coach payload. Forecast slots and personal historical response are kept separate. */
data class ClimateStreamStat(val runs: Int, val samples: Int, val hrMedian: Double, val paceMedianMinKm: Double)
data class ClimateStreamComparison(val deltaHr: Int, val runs: Int, val confidence: String)

data class CoachClimateInsight(
    val slots: List<ClimSlot>,
    val personal: ClimPers,
    val responseByClimate: ClimProfile,
    val streamByClimate: Map<String, ClimateStreamStat>,
    val streamComparison: Map<String, ClimateStreamComparison>,
    val streamSamples: Int,
    val matchedRuns: Int,
    val totalRuns: Int,
    val forecastAvailable: Boolean,
)

/** Android adapters for the original RunSense data module. */
class NativeIntervalsRuntime(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("runsense_native_v1", Context.MODE_PRIVATE)
    private val local = RunSenseRepository(app)
    private val apiKeyVault = ApiKeyVault(app)
    private lateinit var domain: DomainRepository
    private lateinit var icu: IcuHttp

    init {
        val store = object : KeyValueStore {
            override fun read(key: String): String? =
                if (key == DomainRepository.KEY_API) apiKeyVault.read() else prefs.getString(key, null)
            override fun write(key: String, value: String) {
                if (key == DomainRepository.KEY_API) apiKeyVault.write(value)
                else prefs.edit().putString(key, value).apply()
            }
            override fun remove(key: String) {
                if (key == DomainRepository.KEY_API) apiKeyVault.remove()
                else prefs.edit().remove(key).apply()
            }
        }
        val http = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
                withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 30_000
                        connection.useCaches = false
                        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                        val status = connection.responseCode
                        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        if (status in 200..299) {
                            val streamId = Regex("/activity/([^/?]+)/streams\\.json").find(url)?.groupValues?.getOrNull(1)
                            if (!streamId.isNullOrBlank()) local.storeIcuStream(streamId, body)
                        }
                        HttpResponse(status, body)
                    } finally {
                        connection.disconnect()
                    }
                }
        }
        val locator = object : Locator {
            override suspend fun lastLocation(): GeoLoc? = withContext(Dispatchers.IO) {
                val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                manager.getProviders(true).asSequence().mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }.maxByOrNull { it.time }?.let { GeoLoc(it.latitude, it.longitude, t = it.time) }
            }
        }
        icu = IcuHttp(http) { domain.apiKey }
        domain = DomainRepository(store, icu, OpenMeteoHttp(http), null, locator, Clock { System.currentTimeMillis() }, ZoneId.systemDefault())
        domain.restore()
    }

    suspend fun refreshClimateContext(): Int {
        domain.ensureLocation() ?: return 0
        var matched = 0
        repeat(4) {
            val count = domain.climateMatch()
            matched += count
            if (count == 0) return@repeat
        }
        return matched
    }

    suspend fun coachClimateInsight(durationMin: Int, current: id.kamui.runsense.domain.WeatherSnapshot?): CoachClimateInsight? {
        val loc = domain.ensureLocation() ?: return null
        // Complete pending historical weather matching before deriving personal thresholds.
        repeat(4) {
            if (domain.climateMatch() == 0) return@repeat
        }
        val snapshot = domain.data.snapshot(System.currentTimeMillis(), ZoneId.systemDefault())
        val contexts = domain.climate.ctx
        val personal = snapshot.clPers(contexts)
        val response = snapshot.clProfile(contexts, personal)
        val matched = contexts.values.count { !it.na && it.t != null }
        val total = domain.data.acts.count { it.kind == com.runnow.domain.ActKind.RUN && it.km >= 2.0 }
        // Attach the run's hourly weather context to its native per-second HR/speed stream.
        // Aggregate per run first so longer activities do not dominate the climate comparison.
        data class RunStream(val hr: Double, val pace: Double, val samples: Int)
        val grouped = LinkedHashMap<String, MutableList<RunStream>>()
        val stage = snapshot.stage()
        var streamSampleCount = 0
        for (activity in domain.data.acts) {
            if (activity.kind != com.runnow.domain.ActKind.RUN || activity.km < 2.0) continue
            if (snapshot.detectType(activity, stage).type != "easy") continue
            val context = contexts[activity.start] ?: continue
            if (context.na || context.t == null) continue
            val id = activity.icuId ?: activity.lid
            val points = local.streamPoints(id)
            val valid = points.filter { it.heartRate > 40.0 && it.speedMps >= 0.5 && it.speedMps <= 8.0 }
            if (valid.size < 60) continue
            val hr = valid.map { it.heartRate }.average()
            val pace = 1000.0 / valid.map { it.speedMps }.average() / 60.0
            if (!hr.isFinite() || !pace.isFinite()) continue
            val key = clK(context, personal)
            if (key.isBlank()) continue
            grouped.getOrPut(key) { ArrayList() }.add(RunStream(hr, pace, valid.size))
            streamSampleCount += valid.size
        }
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return if (sorted.isEmpty()) 0.0 else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val streamStats = grouped.mapValues { (_, rows) ->
            ClimateStreamStat(rows.size, rows.sumOf { it.samples }, median(rows.map { it.hr }), median(rows.map { it.pace }))
        }
        val streamCmp = LinkedHashMap<String, ClimateStreamComparison>()
        val cool = streamStats["sejuk"]
        if (cool != null && cool.runs >= 3) {
            for ((key, stat) in streamStats) {
                if (key == "sejuk" || stat.runs < 3 || kotlin.math.abs(stat.paceMedianMinKm - cool.paceMedianMinKm) > 0.5) continue
                val n = minOf(stat.runs, cool.runs)
                streamCmp[key] = ClimateStreamComparison(
                    (stat.hrMedian - cool.hrMedian).toInt(), n,
                    if (n < 6) "mulai terbaca" else if (n < 12) "cukup konsisten" else "semakin matang"
                )
            }
        }
        val slots = if (current != null) {
            val nowContext = ClimCtx(
                t = current.temperatureC,
                rh = current.humidityPct.toDouble(),
                at = current.apparentC,
                w = current.windKmh,
                p = current.precipitationMm,
                code = current.weatherCode.toDouble(),
            )
            val forecast = domain.wxGet(loc.lat, loc.lon, "fc", climDate(System.currentTimeMillis(), ZoneId.systemDefault()))
            if (forecast != null) clSlots(
                forecast, durationMin.coerceIn(10, 180).toDouble(), nowContext, personal,
                System.currentTimeMillis(), ZoneId.systemDefault()
            ) else emptyList()
        } else emptyList()
        return CoachClimateInsight(slots, personal, response, streamStats, streamCmp, streamSampleCount, matched, total, slots.isNotEmpty())
    }

    suspend fun autoHeatToday(): Boolean = domain.autoHeat()

    fun climateContexts(): Map<Long, ClimCtx> = domain.climate.ctx

    fun hasApiKey(): Boolean = !domain.apiKey.isNullOrBlank()

    fun apiKeyLastFour(): String? = domain.apiKey?.takeIf { it.isNotBlank() }?.takeLast(4)

    fun snapshot(): Snapshot = domain.data.snapshot(System.currentTimeMillis(), ZoneId.systemDefault())

    fun streamPoints(activityId: String) = local.streamPoints(activityId)

    fun ingestWatchJson(json: String) {
        val parsed = Codec.parseWatchImport(json) ?: return
        domain.ingestWatch(parsed)
    }

    suspend fun ensureStreamFor(activityId: String): Boolean {
        val activity = domain.data.acts.firstOrNull { it.icuId == activityId || it.lid == activityId } ?: return false
        domain.ensureStream(activity)
        val icuId = activity.icuId
        if (local.streamPoints(activityId).isEmpty() && !icuId.isNullOrBlank() && !icuId.startsWith("hw")) {
            val id = java.net.URLEncoder.encode(icuId, "UTF-8").replace("+", "%20")
            try {
                icu.get("activity/$id/streams.json?types=time,distance,heartrate,cadence,velocity_smooth")
            } catch (_: Exception) { }
        }
        return local.streamPoints(activityId).isNotEmpty()
    }

    suspend fun createPlannedWorkout(name: String, date: String, description: String, movingTimeSeconds: Int? = null): String = withContext(Dispatchers.IO) {
        val key = domain.apiKey?.takeIf { it.isNotBlank() && it != "demo" }
            ?: throw IllegalStateException("Hubungkan Intervals.icu dengan API key terlebih dahulu.")
        val parsedDate = runCatching { java.time.LocalDate.parse(date).toString() }.getOrNull()
            ?: throw IllegalArgumentException("Tanggal harus berformat YYYY-MM-DD.")
        require(name.isNotBlank()) { "Nama latihan belum diisi." }
        require(description.isNotBlank()) { "Susunan latihan masih kosong." }
        val body = org.json.JSONObject()
            .put("category", "WORKOUT")
            .put("start_date_local", "${parsedDate}T00:00:00")
            .put("type", "Run")
            .put("name", name.take(120))
            .put("description", description)
        movingTimeSeconds?.takeIf { it > 0 }?.let { body.put("moving_time", it) }
        val connection = URL("https://intervals.icu/api/v1/athlete/0/events").openConnection() as HttpURLConnection
        try {
            val auth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("API_KEY:$key".toByteArray(Charsets.UTF_8))
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", auth)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = response.take(240)
                throw IllegalStateException("Intervals.icu menolak workout (HTTP $status)${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val event = runCatching { org.json.JSONObject(response) }.getOrNull()
            val eventId = event?.optLong("id", 0L) ?: 0L
            // Intervals.icu parses its native workout-description syntax into workout_doc.steps.
            // Do not send workout_doc ourselves: the API parser owns this field.
            val parsedSteps = event?.optJSONObject("workout_doc")?.optJSONArray("steps")?.length() ?: 0
            when {
                eventId <= 0L -> "Workout dikirim, tetapi ID event tidak diterima. Periksa kalender Intervals.icu."
                parsedSteps > 0 -> "Workout berhasil dikirim ke kalender Intervals.icu (ID $eventId · $parsedSteps langkah terstruktur)."
                else -> "Workout tersimpan di kalender Intervals.icu (ID $eventId), tetapi respons belum memuat langkah terstruktur. Periksa deskripsi workout di Intervals.icu sebelum mengandalkannya untuk sinkronisasi perangkat."
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun sync(apiKey: String? = null): String {
        if (!apiKey.isNullOrBlank()) domain.login(apiKey.trim())
        val result = domain.sync(silent = false)
        if (result.ok) local.saveLastSyncAt()
        // Ambil stream untuk seluruh lari Intervals.icu yang belum tersimpan.
        // Stream disimpan lokal saat HTTP request berlangsung, sehingga layar Riwayat
        // cukup membaca cache dan tidak perlu tombol "Muat stream".
        val streamCandidates = domain.data.acts
            .filter { activity ->
                val streamId = activity.icuId
                activity.kind == com.runnow.domain.ActKind.RUN &&
                    activity.min >= 5.0 && activity.km >= 0.3 &&
                    !streamId.isNullOrBlank() && !streamId.startsWith("hw")
            }
            .sortedByDescending { it.start }
        var streamsLoaded = 0
        for (activity in streamCandidates) {
            val id = activity.icuId ?: continue
            if (ensureStreamFor(id)) streamsLoaded++
        }
        val imported = local.importDomainRecords(domain.data.acts, domain.data.wellness)
        return buildString {
            append(if (result.ok) result.message.ifBlank { "Sinkron selesai" } else "Sinkron belum berhasil. Periksa API key dan koneksi internet.")
            if (result.ok) append(" · ${imported.first} aktivitas baru · ${imported.second} hari wellness diperbarui · $streamsLoaded stream lari tersimpan")
        }
    }
}
