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
        val migratedKeys = mutableListOf<String>()
        legacy.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            val prefix = if (key.startsWith("watch_stream_")) "watch" else "icu"
            val id = key.removePrefix("watch_stream_").removePrefix("icu_stream_")
            if (runCatching { streamFile(prefix, id).writeText(raw, Charsets.UTF_8) }.isSuccess) migratedKeys += key
        }
        if (migratedKeys.size == legacy.size) {
            val editor = prefs.edit().putBoolean("streams_file_migration_v1", true)
            migratedKeys.forEach { editor.remove(it) }
            editor.apply()
        }
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
        val id = o.optString("id").trim()
        val startedAt = o.optLong("startedAt", 0L)
        val duration = o.optDouble("durationMin", Double.NaN)
        val distance = o.optDouble("distanceKm", Double.NaN)
        val load = o.optDouble("externalLoad", 0.0)
        if (id.isBlank() || startedAt <= 0L || !duration.isFinite() || duration <= 0.0 ||
            !distance.isFinite() || distance < 0.0) return@mapNotNull null
        ActivityRecord(id, startedAt, o.optString("type", "run").ifBlank { "run" }, duration, distance,
            o.optInt("averageHr").coerceIn(0, 250), load.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            o.optBoolean("quality"), o.optInt("rpe").coerceIn(0, 10), o.optString("feel"), o.optInt("pain").coerceIn(0, 10))
    }.sortedByDescending { it.startedAt }

    fun strength(): List<StrengthRecord> = objects("strength").mapNotNull { o ->
        val id = o.optString("id").trim()
        val date = o.optString("date")
        val duration = o.optInt("durationMin", -1)
        val resistance = o.optDouble("resistanceKg", 0.0)
        if (id.isBlank() || !runCatching { java.time.LocalDate.parse(date) }.isSuccess ||
            duration !in 1..720 || !resistance.isFinite() || resistance < 0.0) return@mapNotNull null
        StrengthRecord(id, date, o.optString("category", "full"), duration, o.optInt("level").coerceIn(0, 10),
            o.optBoolean("completed"), o.optString("exercise"), o.optInt("sets").coerceAtLeast(0),
            o.optInt("reps").coerceAtLeast(0), resistance)
    }.sortedByDescending { it.date }

    private fun validWellnessDate(raw: String): Boolean = isValidWellnessDate(raw)

    private fun finitePositive(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else finitePositiveWellnessMetric(o.optDouble(key, Double.NaN))

    fun wellness(): List<WellnessRecord> {
        val raw = readArray("wellness")
        val cleaned = JSONArray()
        val records = mutableListOf<WellnessRecord>()
        var changed = false
        for (i in 0 until raw.length()) {
            val source = raw.optJSONObject(i)
            if (source == null) { changed = true; continue }
            val date = source.optString("date").trim()
            if (!validWellnessDate(date)) { changed = true; continue }
            val rhr = source.optInt("restingHr", 0).takeIf { it in 25..250 } ?: 0
            val sleep = finitePositive(source, "sleepHours")
            val hrv = finitePositive(source, "hrv")
            if (rhr == 0 && sleep == null && hrv == null) { changed = true; continue }
            val item = JSONObject().put("date", date)
            if (rhr > 0) item.put("restingHr", rhr)
            if (sleep != null) item.put("sleepHours", sleep)
            if (hrv != null) item.put("hrv", hrv)
            if (source.toString() != item.toString()) changed = true
            cleaned.put(item)
            records += WellnessRecord(date, rhr, sleep ?: 0.0, hrv ?: 0.0)
        }
        if (changed || cleaned.length() != raw.length()) writeArray("wellness", cleaned)
        return records.sortedBy { it.date }
    }

    fun profile(): AthleteProfile = AthleteProfile(
        prefs.getInt("rhr", 63).coerceIn(25, 250), prefs.getInt("maxHr", 185).coerceIn(80, 250),
        prefs.getInt("lthr", 160).coerceIn(80, 230), prefs.getInt("ltPace", 390).coerceIn(180, 1800),
        prefs.getInt("heightCm", 0).coerceIn(0, 230),
        prefs.getFloat("weightKg", 0f).toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    )
    fun saveProfile(p: AthleteProfile) {
        val weight = p.weightKg.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(250.0) ?: 0.0
        prefs.edit().putInt("rhr", p.restingHr.coerceIn(25, 250)).putInt("maxHr", p.maxHr.coerceIn(80, 250))
            .putInt("lthr", p.thresholdHr.coerceIn(80, 230))
            .putInt("ltPace", p.thresholdPaceSecPerKm.coerceIn(180, 1800))
            .putInt("heightCm", p.heightCm.coerceIn(0, 230))
            .putFloat("weightKg", weight.toFloat()).apply()
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
        val weather = runCatching {
            val o = JSONObject(raw)
            val temperature = o.optDouble("temperatureC", Double.NaN)
            val apparent = o.optDouble("apparentC", temperature)
            val wind = o.optDouble("windKmh", Double.NaN)
            val precipitation = o.optDouble("precipitationMm", Double.NaN)
            val latitude = o.optDouble("latitude", Double.NaN)
            val longitude = o.optDouble("longitude", Double.NaN)
            val fetchedAt = o.optLong("fetchedAt", 0L)
            val humidity = o.optInt("humidityPct", -1)
            val code = o.optInt("weatherCode", -1)
            require(listOf(temperature, apparent, wind, precipitation, latitude, longitude).all { it.isFinite() })
            require(wind >= 0.0 && precipitation >= 0.0 && fetchedAt > 0L && humidity in 0..100 && code >= 0)
            WeatherSnapshot(temperature, apparent, humidity, wind, precipitation, code, fetchedAt, latitude, longitude)
        }.getOrNull()
        if (weather == null) prefs.edit().remove("current_weather").apply()
        return weather
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
        val humidity = current.optInt("relative_humidity_2m", -1)
        val code = current.optInt("weather_code", -1)
        require(temperature.isFinite() && apparent.isFinite() && wind.isFinite() && precipitation.isFinite() &&
            wind >= 0.0 && precipitation >= 0.0 && humidity in 0..100 && code >= 0) {
            "Data cuaca dari layanan tidak valid. Coba perbarui beberapa saat lagi."
        }
        val weather = WeatherSnapshot(
            temperature,
            apparent,
            humidity,
            wind,
            precipitation,
            code,
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
    fun lastSyncAt(): Long = prefs.getLong("last_sync_at", 0L)
    fun saveLastSyncAt(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_sync_at", timestamp.coerceAtLeast(0L)).apply()
    }
    fun isLightTheme(): Boolean = prefs.getBoolean("light_theme", false)
    fun saveLightTheme(enabled: Boolean) { prefs.edit().putBoolean("light_theme", enabled).apply() }
    fun onboardingComplete(): Boolean = prefs.getBoolean("onboarding_complete", false)
    fun saveOnboardingComplete() { prefs.edit().putBoolean("onboarding_complete", true).apply() }

    fun addActivity(type: String, duration: Double, distance: Double, hr: Int, load: Double, quality: Boolean, rpe: Int = 0, feel: String = "", pain: Int = 0) {
        if (!duration.isFinite() || duration <= 0.0 || !distance.isFinite() || distance < 0.0) return
        val safeLoad = load.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val a = readArray("activities")
        a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("startedAt", System.currentTimeMillis())
            .put("type", type.ifBlank { "run" }).put("durationMin", duration).put("distanceKm", distance)
            .put("averageHr", hr.coerceIn(0, 250)).put("externalLoad", safeLoad).put("quality", quality)
            .put("rpe", rpe.coerceIn(0, 10)).put("feel", feel).put("pain", pain.coerceIn(0, 10)))
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
        if (duration !in 1..720) return
        val resistance = resistanceKg.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val a = readArray("strength")
        a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("date", dateFormat.format(Date()))
            .put("category", category.ifBlank { "full" }).put("durationMin", duration).put("level", level.coerceIn(0, 10))
            .put("completed", completed).put("exercise", exercise).put("sets", sets.coerceAtLeast(0))
            .put("reps", reps.coerceAtLeast(0)).put("resistanceKg", resistance))
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
        val item = JSONObject().put("date", dateFormat.format(Date()))
        if (rhr in 25..250) item.put("restingHr", rhr)
        if (sleep.isFinite() && sleep > 0.0) item.put("sleepHours", sleep)
        if (hrv.isFinite() && hrv > 0.0) item.put("hrv", hrv)
        if (item.length() == 1) return
        val date = item.optString("date")
        val old = readArray("wellness")
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("date") != date) next.put(o)
        }
        next.put(item)
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
                if (title.isNotBlank()) plan[day] = com.runnow.domain.PlanDay(title, item.optDouble("km", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
            }
            com.runnow.domain.Settings(
                style = root.optString("style", "moderate"),
                plan = plan,
                race = root.optString("race", ""),
                raceDist = root.optDouble("raceDist", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                raceGoal = root.optString("raceGoal", ""),
                goal = root.optString("goal", "5K"),
            )
        } catch (_: Exception) { com.runnow.domain.Settings() }
    }

    fun savePlanSettings(settings: com.runnow.domain.Settings) {
        val plan = JSONObject()
        settings.plan.forEach { (day, item) ->
            plan.put(day.toString(), JSONObject().put("title", item.t).put("km", item.km.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0))
        }
        val json = JSONObject()
            .put("style", settings.style)
            .put("plan", plan)
            .put("race", settings.race)
            .put("raceDist", settings.raceDist.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
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
                val time = p.optDouble(0, Double.NaN)
                if (!time.isFinite() || time < 0.0) return@mapNotNull null
                fun finiteAt(index: Int): Double = p.optDouble(index, 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                RawStreamPoint(
                    timeSec = time,
                    heartRate = finiteAt(1),
                    speedMps = finiteAt(2),
                    cadenceRaw = finiteAt(4),
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
        return (0 until time.length()).mapNotNull { i ->
            val t = time.optDouble(i, Double.NaN)
            if (!t.isFinite() || t < 0.0) return@mapNotNull null
            val v = speed?.optDouble(i, 0.0) ?: run {
                val d = distance?.optDouble(i, previousDistance) ?: previousDistance
                val dt = t - previousTime
                val derived = if (dt > 0.0 && d.isFinite() && d >= previousDistance) (d - previousDistance) / dt else 0.0
                if (d.isFinite() && d >= previousDistance) previousDistance = d
                previousTime = t
                derived
            }
            fun finiteAt(series: JSONArray?): Double =
                series?.optDouble(i, 0.0)?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            RawStreamPoint(t, finiteAt(hr), v.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, finiteAt(cadence))
        }
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
            val id = (a.icuId ?: a.lid).trim()
            if (id.isBlank() || a.start <= 0L || !a.min.isFinite() || a.min <= 0.0 ||
                !a.km.isFinite() || a.km < 0.0) return@forEach
            val existed = byId.containsKey(id)
            val item = byId[id] ?: JSONObject()
            val hr = a.avgHr.takeIf { it.isFinite() && it in 0.0..250.0 }?.toInt() ?: 0
            val load = a.load.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            item.put("id", id).put("startedAt", a.start)
                .put("type", when (a.kind) { com.runnow.domain.ActKind.RUN -> "run"; com.runnow.domain.ActKind.WALK -> "walk"; else -> "other" })
                .put("durationMin", a.min).put("distanceKm", a.km).put("averageHr", hr)
                .put("externalLoad", load).put("quality", false)
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
        var wellnessAdded = 0
        incomingWellness.forEach { w ->
            if (!validWellnessDate(w.id)) return@forEach
            val rhr = w.restingHR.takeIf { it.isFinite() && it in 25.0..250.0 }?.toInt()
            val sleep = w.sleepSecs.takeIf { it.isFinite() && it > 0.0 }?.div(3600.0)
            val hrv = w.hrv.takeIf { it.isFinite() && it > 0.0 }
            if (rhr == null && sleep == null && hrv == null) return@forEach
            val item = byDate[w.id] ?: JSONObject().put("date", w.id)
            if (rhr != null) item.put("restingHr", rhr)
            if (sleep != null) item.put("sleepHours", sleep)
            if (hrv != null) item.put("hrv", hrv)
            byDate[w.id] = item
            wellnessAdded++
        }
        val mergedWell = JSONArray()
        byDate.values.filter { validWellnessDate(it.optString("date")) }
            .sortedBy { it.optString("date") }.takeLast(400).forEach { mergedWell.put(it) }
        writeArray("wellness", mergedWell)
        return added to wellnessAdded
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
            val duration = row.optDouble(2, Double.NaN)
            if (start <= 0L || id.isBlank() || !duration.isFinite() || duration <= 0.0) continue
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
            val distance = row.optDouble(3, Double.NaN)
            if (!distance.isFinite() || distance < 0.0) continue
            val hr = row.optDouble(4, 0.0).takeIf { it.isFinite() && it in 0.0..250.0 }?.toInt() ?: 0
            val load = row.optDouble(8, 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val existed = byId.containsKey(id)
            val item = byId[id] ?: JSONObject()
            item.put("id", id)
                .put("startedAt", start)
                .put("type", row.optString(1, "run"))
                .put("durationMin", duration)
                .put("distanceKm", distance)
                .put("averageHr", hr)
                .put("externalLoad", load)
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
        var wellnessAdded = 0
        for (i in 0 until incomingWell.length()) {
            val item = incomingWell.optJSONObject(i) ?: continue
            val date = item.optString("id")
            if (!validWellnessDate(date)) continue
            val rhr = item.optDouble("restingHR", Double.NaN).takeIf { it.isFinite() && it in 25.0..250.0 }?.toInt()
            val sleepSecs = item.optDouble("sleepSecs", Double.NaN)
            val sleep = sleepSecs.takeIf { it.isFinite() && it > 0.0 }?.div(3600.0)
            val hrv = item.optDouble("hrv", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            if (rhr == null && sleep == null && hrv == null) continue
            val dest = byDate[date] ?: JSONObject().put("date", date)
            if (rhr != null) dest.put("restingHr", rhr)
            if (sleep != null) dest.put("sleepHours", sleep)
            if (hrv != null) dest.put("hrv", hrv)
            byDate[date] = dest
            wellnessAdded++
        }
        val mergedWell = JSONArray()
        byDate.values.filter { validWellnessDate(it.optString("date")) }
            .sortedBy { it.optString("date") }.takeLast(400).forEach { mergedWell.put(it) }
        writeArray("wellness", mergedWell)
        return added to wellnessAdded
    }

    data class BackupImportResult(val activitiesAdded: Int, val strengthAdded: Int, val wellnessAdded: Int, val settingsAdded: Int, val skipped: Int)

    private val backupSettingKeys = setOf(
        "rhr", "maxHr", "lthr", "ltPace", "heightCm", "weightKg",
        "runwalk_enabled", "runwalk_run_seconds", "runwalk_walk_seconds",
        "weekly_strength_target", "parq_answers", "parq_updated_at", "last_sync_at",
        "plan_settings", "light_theme", "onboarding_complete"
    )

    /** Portable backup excludes credentials, transient weather, and large stream caches. */
    fun exportBackupJson(): String {
        val settings = JSONObject()
        backupSettingKeys.forEach { key ->
            if (prefs.contains(key)) {
                val value = prefs.all[key]
                if (value != null) settings.put(key, value)
            }
        }
        return JSONObject()
            .put("schema_version", 1)
            .put("exported_at", System.currentTimeMillis())
            .put("settings", settings)
            .put("activities", readArray("activities"))
            .put("strength", readArray("strength"))
            .put("wellness", readArray("wellness"))
            .toString(2)
    }

    /** Restore is additive: existing records/settings win; only missing stable IDs/keys are added. */
    fun importBackupJson(raw: String): BackupImportResult {
        val root = JSONObject(raw)
        val schema = root.optInt("schema_version", 0)
        require(schema in 0..1) { "Versi backup tidak didukung (schema $schema)." }
        var skipped = 0

        fun mergeRecords(key: String, identity: (JSONObject) -> String, valid: (JSONObject) -> Boolean = { true }): Pair<Int, Int> {
            val current = readArray(key)
            val seen = LinkedHashSet<String>()
            val merged = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                val id = identity(item)
                if (id.isNotBlank() && seen.add(id)) merged.put(item)
            }
            var added = 0
            val incoming = root.optJSONArray(key) ?: JSONArray()
            for (i in 0 until incoming.length()) {
                val item = incoming.optJSONObject(i)
                if (item == null) {
                    skipped++
                    continue
                }
                val id = identity(item)
                if (id.isBlank() || !valid(item) || !seen.add(id)) {
                    skipped++
                    continue
                }
                merged.put(item)
                added++
            }
            writeArray(key, merged)
            return added to incoming.length()
        }

        val acts = mergeRecords("activities", { it.optString("id") }) { item ->
            item.optLong("startedAt", -1L) > 0L &&
                item.optDouble("durationMin", Double.NaN).let { it.isFinite() && it > 0.0 } &&
                item.optDouble("distanceKm", Double.NaN).let { it.isFinite() && it >= 0.0 }
        }
        val strengthItems = mergeRecords("strength", { it.optString("id") }) { item ->
            runCatching { java.time.LocalDate.parse(item.optString("date")) }.isSuccess &&
                item.optInt("durationMin", -1) >= 0
        }
        val wellnessItems = mergeRecords("wellness", { it.optString("date") }) { item ->
            val date = item.optString("date")
            validWellnessDate(date) && (
                item.optInt("restingHr", 0) in 25..250 ||
                    finitePositive(item, "sleepHours") != null ||
                    finitePositive(item, "hrv") != null
            )
        }

        var settingsAdded = 0
        val incomingSettings = root.optJSONObject("settings") ?: if (schema == 0) root else JSONObject()
        val editor = prefs.edit()
        for (key in backupSettingKeys) {
            if (prefs.contains(key) || !incomingSettings.has(key) || incomingSettings.isNull(key)) {
                if (incomingSettings.has(key)) skipped++
                continue
            }
            val value = incomingSettings.get(key)
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> if (value.isFinite()) editor.putFloat(key, value.toFloat())
                is Float -> if (value.isFinite()) editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
            settingsAdded++
        }
        editor.apply()
        val totalIncoming = acts.second + strengthItems.second + wellnessItems.second + incomingSettings.length()
        val totalAdded = acts.first + strengthItems.first + wellnessItems.first + settingsAdded
        skipped = maxOf(skipped, totalIncoming - totalAdded)
        return BackupImportResult(acts.first, strengthItems.first, wellnessItems.first, settingsAdded, skipped)
    }

    private fun objects(key: String): List<JSONObject> { val a = readArray(key); return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
    private fun readArray(k: String): JSONArray = try { JSONArray(prefs.getString(k, "[]")) } catch (_: Exception) { JSONArray() }
    private fun writeArray(k: String, v: JSONArray) { prefs.edit().putString(k, v.toString()).apply() }
}
