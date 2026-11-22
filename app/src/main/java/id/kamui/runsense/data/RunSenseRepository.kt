package id.kamui.runsense.data

import android.content.Context
import android.location.LocationManager
import java.net.HttpURLConnection
import java.net.URL
import id.kamui.runsense.domain.*
import id.kamui.runsense.domain.RawStreamPoint
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RunSenseRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("runsense_native_v1", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val streamDirectory by lazy { java.io.File(appContext.filesDir, "streams").apply { mkdirs() } }
    private fun streamFile(prefix: String, id: String) = java.io.File(streamDirectory, "$prefix-${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
    private fun migrateLegacyStreamsOnce() {
        if (prefs.getBoolean("streams_file_migration_v1", false)) return
        val legacy = prefs.all.filterKeys { it.startsWith("icu_stream_") || it.startsWith("watch_stream_") }
        legacy.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            val prefix = if (key.startsWith("watch_stream_")) "watch" else "icu"
            val id = key.removePrefix("watch_stream_").removePrefix("icu_stream_")
            runCatching { streamFile(prefix, id).writeText(raw, Charsets.UTF_8) }
        }
        prefs.edit().putBoolean("streams_file_migration_v1", true).apply()
        if (legacy.isNotEmpty()) prefs.edit().remove(*legacy.keys.toTypedArray()).apply()
        pruneStreams()
    }
    private fun pruneStreams() {
        streamDirectory.listFiles()?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
    }
    fun storeIcuStream(activityId: String, json: String) {
        migrateLegacyStreamsOnce()
        streamFile("icu", activityId).writeText(json, Charsets.UTF_8)
        pruneStreams()
    }
    private fun storeWatchStream(activityId: String, json: String) {
        migrateLegacyStreamsOnce()
        streamFile("watch", activityId).writeText(json, Charsets.UTF_8)
        pruneStreams()
    }

    fun activities(): List<ActivityRecord> = objects("activities").mapNotNull { o ->
        try { ActivityRecord(o.getString("id"), o.getLong("startedAt"), o.getString("type"), o.getDouble("durationMin"), o.getDouble("distanceKm"), o.optInt("averageHr"), o.optDouble("externalLoad"), o.optBoolean("quality"), o.optInt("rpe"), o.optString("feel"), o.optInt("pain")) } catch (_: Exception) { null }
    }.sortedByDescending { it.startedAt }

    fun strength(): List<StrengthRecord> = objects("strength").mapNotNull { o ->
        try { StrengthRecord(o.getString("id"), o.getString("date"), o.getString("category"), o.optInt("durationMin"), o.optInt("level"), o.optBoolean("completed"), o.optString("exercise"), o.optInt("sets"), o.optInt("reps"), o.optDouble("resistanceKg")) } catch (_: Exception) { null }
    }.sortedByDescending { it.date }

    private fun validWellnessDate(raw: String): Boolean {
        val parsed = runCatching { java.time.LocalDate.parse(raw) }.getOrNull() ?: return false
        return !parsed.isAfter(java.time.LocalDate.now())
    }

    private fun finitePositive(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key, Double.NaN).takeIf { it.isFinite() && it > 0.0 }

    fun wellness(): List<WellnessRecord> = objects("wellness").mapNotNull { o ->
        val date = o.optString("date").trim()
        if (!validWellnessDate(date)) return@mapNotNull null
        val rhr = o.optInt("restingHr", 0).takeIf { it in 25..250 } ?: 0
        val sleep = finitePositive(o, "sleepHours")
        val hrv = finitePositive(o, "hrv")
        if (rhr == 0 && sleep == null && hrv == null) return@mapNotNull null
        WellnessRecord(date, rhr, sleep ?: 0.0, hrv ?: 0.0)
    }.sortedBy { it.date }

    fun profile(): AthleteProfile = AthleteProfile(
        prefs.getInt("rhr", 63), prefs.getInt("maxHr", 185), prefs.getInt("lthr", 160),
        prefs.getInt("ltPace", 390), prefs.getInt("heightCm", 0), prefs.getFloat("weightKg", 0f).toDouble()
    )
    fun saveProfile(p: AthleteProfile) {
        prefs.edit().putInt("rhr", p.restingHr).putInt("maxHr", p.maxHr).putInt("lthr", p.thresholdHr)
            .putInt("ltPace", p.thresholdPaceSecPerKm).putInt("heightCm", p.heightCm.coerceIn(0, 230))
            .putFloat("weightKg", p.weightKg.toFloat().coerceIn(0f, 250f)).apply()
    }
    fun runWalkSettings(): RunWalkSettings = RunWalkSettings(
        prefs.getBoolean("runwalk_enabled", false),
        prefs.getInt("runwalk_run_seconds", 225).coerceIn(10, 3600),
        prefs.getInt("runwalk_walk_seconds", 30).coerceIn(10, 3600)
    )
    fun saveRunWalkSettings(value: RunWalkSettings) {
        prefs.edit().putBoolean("runwalk_enabled", value.enabled)
            .putInt("runwalk_run_seconds", value.runSeconds.coerceIn(10, 3600))
            .putInt("runwalk_walk_seconds", value.walkSeconds.coerceIn(10, 3600)).apply()
    }
    fun currentWeather(): WeatherSnapshot? {
        val raw = prefs.getString("current_weather", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            WeatherSnapshot(o.getDouble("temperatureC"), o.getDouble("apparentC"), o.getInt("humidityPct"),
                o.getDouble("windKmh"), o.getDouble("precipitationMm"), o.getInt("weatherCode"),
                o.getLong("fetchedAt"), o.getDouble("latitude"), o.getDouble("longitude"))
        }.getOrNull()
    }
    fun fetchCurrentWeather(): WeatherSnapshot {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = manager.getProviders(true).asSequence().mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time } ?: throw IllegalStateException("Lokasi terakhir belum tersedia. Aktifkan lokasi perangkat lalu coba lagi.")
        val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,precipitation,weather_code&timezone=auto"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
        }
        val root = try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Layanan cuaca tidak merespons.")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        val current = root.getJSONObject("current")
        val temperature = current.optDouble("temperature_2m", Double.NaN)
        val apparent = current.optDouble("apparent_temperature", temperature)
        val wind = current.optDouble("wind_speed_10m", 0.0)
        val precipitation = current.optDouble("precipitation", 0.0)
        require(temperature.isFinite() && apparent.isFinite() && wind.isFinite() && precipitation.isFinite()) {
            "Data cuaca dari layanan tidak valid. Coba perbarui beberapa saat lagi."
        }
        val weather = WeatherSnapshot(
            temperature,
            apparent,
            current.optInt("relative_humidity_2m", 0).coerceIn(0, 100),
            wind.coerceAtLeast(0.0),
            precipitation.coerceAtLeast(0.0),
            current.optInt("weather_code", 0),
            System.currentTimeMillis(), location.latitude, location.longitude
        )
        prefs.edit().putString("current_weather", JSONObject()
            .put("temperatureC", weather.temperatureC).put("apparentC", weather.apparentC)
            .put("humidityPct", weather.humidityPct).put("windKmh", weather.windKmh)
            .put("precipitationMm", weather.precipitationMm).put("weatherCode", weather.weatherCode)
            .put("fetchedAt", weather.fetchedAt).put("latitude", weather.latitude)
            .put("longitude", weather.longitude).toString()).apply()
        return weather
    }
    fun isLightTheme(): Boolean = prefs.getBoolean("light_theme", false)
    fun saveLightTheme(enabled: Boolean) { prefs.edit().putBoolean("light_theme", enabled).apply() }
    fun onboardingComplete(): Boolean = prefs.getBoolean("onboarding_complete", false)
    fun saveOnboardingComplete() { prefs.edit().putBoolean("onboarding_complete", true).apply() }

    fun addActivity(type: String, duration: Double, distance: Double, hr: Int, load: Double, quality: Boolean, rpe: Int = 0, feel: String = "", pain: Int = 0) {
        val a = readArray("activities")
        a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("startedAt", System.currentTimeMillis()).put("type", type).put("durationMin", duration).put("distanceKm", distance).put("averageHr", hr).put("externalLoad", load).put("quality", quality).put("rpe", rpe.coerceIn(0, 10)).put("feel", feel).put("pain", pain.coerceIn(0, 10)))
        writeArray("activities", a)
    }

    fun updateActivityCheckin(id: String, rpe: Int, feel: String, pain: Int) {
        val old = readArray("activities")
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                item.put("rpe", rpe.coerceIn(0, 10))
                    .put("feel", feel)
                    .put("pain", pain.coerceIn(0, 10))
            }
            next.put(item)
        }
        writeArray("activities", next)
    }

    fun addStrength(category: String, duration: Int, level: Int, completed: Boolean, exercise: String = "", sets: Int = 0, reps: Int = 0, resistanceKg: Double = 0.0) {
        val a = readArray("strength")
        a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("date", dateFormat.format(Date())).put("category", category).put("durationMin", duration).put("level", level).put("completed", completed).put("exercise", exercise).put("sets", sets.coerceAtLeast(0)).put("reps", reps.coerceAtLeast(0)).put("resistanceKg", resistanceKg.coerceAtLeast(0.0)))
        writeArray("strength", a)
    }

    fun weeklyStrengthTarget(): Int = prefs.getInt("weekly_strength_target", 2).coerceIn(0, 7)
    fun saveWeeklyStrengthTarget(days: Int) { prefs.edit().putInt("weekly_strength_target", days.coerceIn(0, 7)).apply() }

    fun parqAnswers(): List<Boolean?> {
        val raw = prefs.getString("parq_answers", null) ?: return List(7) { null }
        return try { val a = JSONArray(raw); List(7) { i -> if (i >= a.length() || a.isNull(i)) null else a.optBoolean(i) } } catch (_: Exception) { List(7) { null } }
    }
    fun saveParqAnswers(answers: List<Boolean?>) {
        val a = JSONArray(); (0 until 7).forEach { i -> val value = answers.getOrNull(i); if (value == null) a.put(JSONObject.NULL) else a.put(value) }
        prefs.edit().putString("parq_answers", a.toString()).putLong("parq_updated_at", System.currentTimeMillis()).apply()
    }
    fun parqUpdatedAt(): Long = prefs.getLong("parq_updated_at", 0L)

    fun addWellness(rhr: Int, sleep: Double, hrv: Double) {
        val date = dateFormat.format(Date()); val old = readArray("wellness"); val next = JSONArray()
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optString("date") != date) next.put(o) }
        next.put(JSONObject().put("date", date).put("restingHr", rhr).put("sleepHours", sleep).put("hrv", hrv))
        writeArray("wellness", next)
    }



    /** Native weekly plan and race target; kept separately from imported watch snapshots. */
    fun planSettings(): com.runnow.domain.Settings {
        val raw = prefs.getString("plan_settings", null) ?: return com.runnow.domain.Settings()
        return try {
            val root = JSONObject(raw)
            val planJson = root.optJSONObject("plan") ?: JSONObject()
            val plan = LinkedHashMap<Int, com.runnow.domain.PlanDay>()
            for (key in planJson.keys()) {
                val day = key.toIntOrNull() ?: continue
                val item = planJson.optJSONObject(key) ?: continue
                val title = item.optString("title").trim()
                if (title.isNotBlank()) plan[day] = com.runnow.domain.PlanDay(title, item.optDouble("km", 0.0).coerceAtLeast(0.0))
            }
            com.runnow.domain.Settings(
                style = root.optString("style", "moderate"),
                plan = plan,
                race = root.optString("race", ""),
                raceDist = root.optDouble("raceDist", 0.0).coerceAtLeast(0.0),
                raceGoal = root.optString("raceGoal", ""),
                goal = root.optString("goal", "5K"),
            )
        } catch (_: Exception) { com.runnow.domain.Settings() }
    }

    fun savePlanSettings(settings: com.runnow.domain.Settings) {
        val plan = JSONObject()
        settings.plan.forEach { (day, item) ->
            plan.put(day.toString(), JSONObject().put("title", item.t).put("km", item.km))
        }
        val json = JSONObject()
            .put("style", settings.style)
            .put("plan", plan)
            .put("race", settings.race)
            .put("raceDist", settings.raceDist)
            .put("raceGoal", settings.raceGoal)
            .put("goal", settings.goal)
        prefs.edit().putString("plan_settings", json.toString()).apply()
    }

    fun streamPoints(activityId: String): List<RawStreamPoint> {
        migrateLegacyStreamsOnce()
        val watchRaw = streamFile("watch", activityId).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        if (watchRaw != null) {
            val points = try { JSONArray(watchRaw) } catch (_: Exception) { JSONArray() }
            return (0 until points.length()).mapNotNull { i ->
                val p = points.optJSONArray(i) ?: return@mapNotNull null
                if (p.length() < 5) return@mapNotNull null
                RawStreamPoint(
                    timeSec = p.optDouble(0, Double.NaN),
                    heartRate = p.optDouble(1, 0.0),
                    speedMps = p.optDouble(2, 0.0),
                    cadenceRaw = p.optDouble(4, 0.0),
                )
            }
        }
        val raw = streamFile("icu", activityId).takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: return emptyList()
        return parseIntervalsStream(raw)
    }

    private fun parseIntervalsStream(raw: String): List<RawStreamPoint> {
        val rootArray = try { JSONArray(raw) } catch (_: Exception) { null }
        val rootObject = if (rootArray == null) try { JSONObject(raw) } catch (_: Exception) { null } else null
        fun series(type: String): JSONArray? {
            if (rootArray != null) {
                for (i in 0 until rootArray.length()) {
                    val item = rootArray.optJSONObject(i) ?: continue
                    if (item.optString("type") == type) return item.optJSONArray("data")
                }
            }
            val value = rootObject?.opt(type)
            return when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("data")
                else -> null
            }
        }
        val time = series("time") ?: return emptyList()
        val cadence = series("cadence")
        val hr = series("heartrate")
        val speed = series("velocity_smooth")
        val distance = series("distance")
        var previousTime = 0.0
        var previousDistance = 0.0
        return (0 until time.length()).map { i ->
            val t = time.optDouble(i, Double.NaN)
            val v = speed?.optDouble(i, 0.0) ?: run {
                val d = distance?.optDouble(i, 0.0) ?: previousDistance
                val dt = t - previousTime
                val derived = if (dt > 0) (d - previousDistance) / dt else 0.0
                previousDistance = d
                previousTime = t
                derived
            }
            RawStreamPoint(t, hr?.optDouble(i, 0.0) ?: 0.0, v, cadence?.optDouble(i, 0.0) ?: 0.0)
        }.filter { it.timeSec.isFinite() }
    }

    /** Merge Intervals.icu / native domain repository records without duplicating stable activity IDs. */
    fun importDomainRecords(
        incomingActivities: List<com.runnow.domain.Act>,
        incomingWellness: List<com.runnow.domain.Wellness>,
    ): Pair<Int, Int> {
        val old = readArray("activities")
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) byId[id] = item
        }
        var added = 0
        incomingActivities.forEach { a ->
            val id = a.icuId ?: a.lid
            val existed = byId.containsKey(id)
            val item = byId[id] ?: JSONObject()
            item.put("id", id).put("startedAt", a.start)
                .put("type", when (a.kind) { com.runnow.domain.ActKind.RUN -> "run"; com.runnow.domain.ActKind.WALK -> "walk"; else -> "other" })
                .put("durationMin", a.min).put("distanceKm", a.km).put("averageHr", a.avgHr.toInt())
                .put("externalLoad", a.load).put("quality", false)
            byId[id] = item
            if (!existed) added++
        }
        val merged = JSONArray()
        byId.values.sortedBy { it.optLong("startedAt") }.takeLast(1200).forEach { merged.put(it) }
        writeArray("activities", merged)

        val oldWell = readArray("wellness")
        val byDate = LinkedHashMap<String, JSONObject>()
        for (i in 0 until oldWell.length()) {
            val item = oldWell.optJSONObject(i) ?: continue
            val date = item.optString("date")
            if (date.isNotBlank()) byDate[date] = item
        }
        incomingWellness.forEach { w ->
            val item = byDate[w.id] ?: JSONObject().put("date", w.id)
            if (w.restingHR > 0) item.put("restingHr", w.restingHR.toInt())
            if (w.sleepSecs > 0) item.put("sleepHours", w.sleepSecs / 3600.0)
            if (w.hrv > 0) item.put("hrv", w.hrv)
            byDate[w.id] = item
        }
        val mergedWell = JSONArray()
        byDate.values.sortedBy { it.optString("date") }.takeLast(400).forEach { mergedWell.put(it) }
        writeArray("wellness", mergedWell)
        return added to incomingWellness.size
    }

    /** Merge Huawei WatchImport JSON into the same local history used by native Coach and Progress. */
    fun importWatchJson(json: String): Pair<Int, Int> {
        val root = JSONObject(json)
        val oldActs = readArray("activities")
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until oldActs.length()) {
            val item = oldActs.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) byId[id] = item
        }
        val workouts = root.optJSONArray("workouts") ?: JSONArray()
        for (i in 0 until workouts.length()) {
            val workout = workouts.optJSONObject(i) ?: continue
            val streamId = workout.optString("iid")
            val points = workout.optJSONArray("pts")
            if (streamId.isNotBlank() && points != null && points.length() > 0) {
                storeWatchStream(streamId, points.toString())
            }
        }
        val incomingActs = root.optJSONArray("acts") ?: JSONArray()
        var added = 0
        for (i in 0 until incomingActs.length()) {
            val row = incomingActs.optJSONArray(i) ?: continue
            if (row.length() < 10) continue
            val start = row.optLong(0)
            val id = row.optString(9).ifBlank { "hw$start" }
            if (row.optString(1) == "weight") {
                val strengthItems = readArray("strength")
                val exists = (0 until strengthItems.length()).any { strengthItems.optJSONObject(it)?.optString("id") == id }
                if (!exists) {
                    strengthItems.put(JSONObject().put("id", id)
                        .put("date", dateFormat.format(Date(start)))
                        .put("category", "full")
                        .put("durationMin", row.optDouble(2).toInt())
                        .put("level", 1)
                        .put("completed", true))
                    writeArray("strength", strengthItems)
                }
                continue
            }
            val existed = byId.containsKey(id)
            val item = byId[id] ?: JSONObject()
            item.put("id", id)
                .put("startedAt", start)
                .put("type", row.optString(1, "run"))
                .put("durationMin", row.optDouble(2))
                .put("distanceKm", row.optDouble(3))
                .put("averageHr", row.optDouble(4).toInt())
                .put("externalLoad", row.optDouble(8))
                .put("quality", false)
            byId[id] = item
            if (!existed) added++
        }
        val mergedActs = JSONArray()
        byId.values.sortedBy { it.optLong("startedAt") }.takeLast(1200).forEach { mergedActs.put(it) }
        writeArray("activities", mergedActs)

        val oldWell = readArray("wellness")
        val byDate = LinkedHashMap<String, JSONObject>()
        for (i in 0 until oldWell.length()) {
            val item = oldWell.optJSONObject(i) ?: continue
            val date = item.optString("date")
            if (date.isNotBlank()) byDate[date] = item
        }
        val incomingWell = root.optJSONArray("wellness") ?: JSONArray()
        for (i in 0 until incomingWell.length()) {
            val item = incomingWell.optJSONObject(i) ?: continue
            val date = item.optString("id")
            if (date.isBlank()) continue
            val dest = byDate[date] ?: JSONObject().put("date", date)
            if (item.has("restingHR")) dest.put("restingHr", item.optDouble("restingHR").toInt())
            if (item.has("sleepSecs")) dest.put("sleepHours", item.optDouble("sleepSecs") / 3600.0)
            if (item.has("hrv")) dest.put("hrv", item.optDouble("hrv"))
            byDate[date] = dest
        }
        val mergedWell = JSONArray()
        byDate.values.sortedBy { it.optString("date") }.takeLast(400).forEach { mergedWell.put(it) }
        writeArray("wellness", mergedWell)
        return added to incomingWell.length()
    }

    private fun objects(key: String): List<JSONObject> { val a = readArray(key); return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    private fun readArray(k: String): JSONArray = try { JSONArray(prefs.getString(k, "[]")) } catch (_: Exception) { JSONArray() }
    private fun writeArray(k: String, v: JSONArray) { prefs.edit().putString(k, v.toString()).apply() }
}
