package id.kamui.runsense.domain

import org.junit.Assert.*
import org.junit.Test

class TrainingEngineTest {
    @Test fun cadenceStreamIsNormalizedBeforeClassification() {
        assertEquals(160.0, TrainingEngine.cadenceSpm(80.0)!!, 0.001)
        assertEquals("Lari", TrainingEngine.classifyCadenceWindow(160.0))
        assertEquals("Jalan", TrainingEngine.classifyCadenceWindow(130.0))
        assertNull(TrainingEngine.cadenceSpm(0.0))
    }

    @Test fun externalTrainingLoadTakesPriority() {
        val a = ActivityRecord("a", 1L, "run", 30.0, 4.0, 150, 72.0)
        assertEquals(72.0, TrainingEngine.activityLoad(a, AthleteProfile()), 0.001)
    }

    @Test fun runLoadUsesHeartRateReserveWhenNoExternalLoadExists() {
        val a = ActivityRecord("a", 1L, "run", 60.0, 8.0, 150)
        val load = TrainingEngine.activityLoad(a, AthleteProfile(60, 180))
        assertTrue(load > 0.0)
        assertTrue(load < 130.0)
    }

    @Test fun twoQualityRunsLockAdditionalQualityRecommendation() {
        val now = System.currentTimeMillis()
        val runs = listOf(
            ActivityRecord("1", now - 86_400_000L, "run", 35.0, 5.0, 160, quality = true),
            ActivityRecord("2", now - 3 * 86_400_000L, "run", 30.0, 4.0, 158, quality = true)
        )
        val summary = TrainingEngine.summarize(runs, emptyList(), emptyList(), AthleteProfile(), now)
        assertEquals(2, summary.qualitySessions7d)
        assertTrue(summary.recommendation.contains("Hindari quality"))
    }

    @Test fun completedLegStrengthChangesCoachRecommendation() {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val strength = listOf(StrengthRecord("s", date, "leg", 30, 4, true))
        val summary = TrainingEngine.summarize(emptyList(), strength, emptyList(), AthleteProfile(), now)
        assertTrue(summary.legStrengthLoad48h >= 3.4)
        assertTrue(summary.recommendation.contains("strength"))
    }

    @Test fun unfinishedStrengthDoesNotAffectCoachLoad() {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val strength = listOf(StrengthRecord("s", date, "leg", 30, 4, false))
        val load = TrainingEngine.strengthLoad48h(strength, now)
        assertEquals(0.0, load.first, 0.001)
        assertEquals(0.0, load.second, 0.001)
    }

    @Test fun completedLegStrengthIsIncludedIn48HourLoad() {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val strength = listOf(StrengthRecord("s", date, "leg", 20, 2, true))
        val load = TrainingEngine.strengthLoad48h(strength, now)
        assertTrue(load.first > 0.0)
        assertEquals(load.first, load.second, 0.001)
    }
    @Test fun strengthSetsRepsAndExternalLoadIncreaseCoachLoad() {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val baseline = StrengthRecord("base", date, "leg", 20, 2, true, "Squat", 3, 10, 0.0)
        val loaded = StrengthRecord("loaded", date, "leg", 20, 2, true, "Squat", 4, 12, 20.0)
        val baselineDose = TrainingEngine.strengthLoad48h(listOf(baseline), now).first
        val loadedDose = TrainingEngine.strengthLoad48h(listOf(loaded), now).first
        assertTrue("Set, repetisi, dan beban eksternal harus ikut dihitung", loadedDose > baselineDose)
    }

    @Test fun strengthVolumeAndExternalLoadMultipliersAreCapped() {
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val extreme = StrengthRecord("max", date, "leg", 30, 4, true, "Squat", 20, 50, 120.0)
        val dose = TrainingEngine.strengthLoad48h(listOf(extreme), now).first
        val expectedMaximum = 1.25 * (1.0 + 4 * 0.45) * 2.0 * 1.5
        assertEquals(expectedMaximum, dose, 0.001)
    }

    @Test fun cadenceAnalyzerCreatesFiveSecondRunWalkSegments() {
        val points = (0..19).map { second ->
            RawStreamPoint(
                timeSec = second.toDouble(),
                heartRate = 150.0,
                speedMps = if (second < 10) 2.8 else 2.0,
                cadenceRaw = if (second < 10) 80.0 else 65.0
            )
        }
        val result = CadenceAnalyzer.analyze(points, 5)
        assertEquals(4, result.windows.size)
        assertEquals(160.0, result.windows[0].cadenceSpm!!, 0.001)
        assertEquals("Lari", result.windows[0].mode)
        assertEquals(130.0, result.windows[2].cadenceSpm!!, 0.001)
        assertEquals("Jalan", result.windows[2].mode)
        assertEquals(2, result.runningWindows)
        assertEquals(2, result.walkingWindows)
        assertEquals(160, result.averageRunningCadence)
    }



    @Test fun readinessBaselineIsIndependentOfWellnessImportOrder() {
        val now = System.currentTimeMillis()
        fun day(offset: Int) = java.time.LocalDate.now().minusDays(offset.toLong()).toString()
        val chronological = (8 downTo 1).map { offset ->
            WellnessRecord(day(offset), restingHr = if (offset <= 3) 70 else 60, sleepHours = 7.0)
        }
        val shuffled = listOf(chronological[7], chronological[6], chronological[5], chronological[0],
            chronological[1], chronological[2], chronological[3], chronological[4])
        val orderedResult = TrainingEngine.summarize(emptyList(), emptyList(), chronological, AthleteProfile(), now)
        val shuffledResult = TrainingEngine.summarize(emptyList(), emptyList(), shuffled, AthleteProfile(), now)
        assertEquals("Urutan impor tidak boleh mengubah readiness", orderedResult.readiness, shuffledResult.readiness)
    }

    @Test fun highStrengthVolumeAndResistanceAffectPlannedSessionAdvice() {
        val planned = java.time.LocalDate.now().plusDays(1)
        val strength = listOf(
            StrengthRecord("heavy", planned.minusDays(1).toString(), "leg", 30, 1, true, "Squat", 4, 12, 20.0)
        )
        val summary = TrainingEngine.summarize(emptyList(), strength, emptyList(), AthleteProfile())
        val advice = TrainingEngine.advisePlannedSession("Interval", planned, emptyList(), strength, summary)
        assertNotNull("Volume dan beban eksternal harus ikut menjadi guardrail sesi berikutnya", advice)
        assertEquals("TINJAU ULANG SESI", advice?.status)
    }

    @Test fun completedLegStrengthFlagsUpcomingQualitySession() {
        val planned = java.time.LocalDate.now().plusDays(2)
        val strength = listOf(StrengthRecord("s", planned.minusDays(1).toString(), "leg", 30, 3, true))
        val summary = TrainingEngine.summarize(emptyList(), strength, emptyList(), AthleteProfile())
        val advice = TrainingEngine.advisePlannedSession("Interval", planned, emptyList(), strength, summary)
        assertNotNull(advice)
        assertEquals("TINJAU ULANG SESI", advice?.status)
    }

    @Test fun unfinishedLegStrengthDoesNotFlagUpcomingQualitySession() {
        val planned = java.time.LocalDate.now().plusDays(2)
        val strength = listOf(StrengthRecord("s", planned.minusDays(1).toString(), "leg", 30, 3, false))
        val summary = TrainingEngine.summarize(emptyList(), strength, emptyList(), AthleteProfile())
        val advice = TrainingEngine.advisePlannedSession("Interval", planned, emptyList(), strength, summary)
        assertNull(advice)
    }

    @Test fun recentQualityRunFlagsAnotherDemandingPlannedSession() {
        val planned = java.time.LocalDate.now().plusDays(1)
        val runTime = planned.minusDays(1).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val runs = listOf(ActivityRecord("q", runTime, "run", 35.0, 5.0, 160, quality = true))
        val summary = TrainingEngine.summarize(runs, emptyList(), emptyList(), AthleteProfile())
        val advice = TrainingEngine.advisePlannedSession("Tempo", planned, runs, emptyList(), summary)
        assertEquals("JARAK QUALITY BERDEKATAN", advice?.status)
    }



    @Test fun meaningfulPainCheckinImmediatelyChangesReadinessAndRecommendation() {
        val now = System.currentTimeMillis()
        val run = ActivityRecord("pain", now - 60_000L, "run", 30.0, 4.0, 145, rpe = 6, feel = "normal", pain = 4)
        val summary = TrainingEngine.summarize(listOf(run), emptyList(), emptyList(), AthleteProfile(), now)
        assertEquals(58, summary.readiness)
        assertTrue(summary.recommendation.contains("nyeri bermakna"))
        assertTrue(summary.rationale.any { it.contains("nyeri >=4/10") })
    }

    @Test fun repeatedHighRpeCheckinsReduceReadinessWithoutSingleSessionOverreaction() {
        val now = System.currentTimeMillis()
        val runs = listOf(
            ActivityRecord("r1", now - 60_000L, "run", 30.0, 4.0, 145, rpe = 9, feel = "sangat berat"),
            ActivityRecord("r2", now - 2 * 86_400_000L, "run", 28.0, 3.5, 142, rpe = 8, feel = "berat")
        )
        val summary = TrainingEngine.summarize(runs, emptyList(), emptyList(), AthleteProfile(), now)
        assertEquals(64, summary.readiness)
        assertTrue(summary.recommendation.contains("terasa berat"))
    }

    @Test fun oldCheckinsDoNotChangeCurrentReadiness() {
        val now = System.currentTimeMillis()
        val old = ActivityRecord("old", now - 9 * 86_400_000L, "run", 30.0, 4.0, 145, rpe = 9, feel = "sangat berat", pain = 5)
        val summary = TrainingEngine.summarize(listOf(old), emptyList(), emptyList(), AthleteProfile(), now)
        assertNull(summary.readiness)
    }

}
