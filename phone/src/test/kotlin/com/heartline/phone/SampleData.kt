// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import com.heartline.phone.ui.model.AlertUi
import com.heartline.phone.ui.model.BodyDetailUi
import com.heartline.phone.ui.model.BodyEntryUi
import com.heartline.shared.body.BodyComposition
import com.heartline.shared.profile.Sex
import com.heartline.phone.ui.model.BpHomeUi
import com.heartline.phone.ui.model.MetricDetailUi
import com.heartline.phone.ui.model.MetricReadingUi
import com.heartline.phone.ui.model.BpReadingUi
import com.heartline.phone.ui.model.EcgListState
import com.heartline.phone.ui.model.HeartRateUi
import com.heartline.shared.hr.AlertKind
import com.heartline.phone.ui.model.EcgRecordUi
import com.heartline.phone.ui.model.HomeState
import com.heartline.phone.ui.model.TileValue
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.Metric
import com.heartline.shared.model.Symptom
import com.heartline.shared.sample.SyntheticEcg

/** Deterministic UI state for screenshots (fixed strings, fixed seeds, no clock). */
object SampleData {
    private fun ecg(bpm: Double, irregular: Double = 0.02, seed: Int = 1) =
        SyntheticEcg.generate(durationSec = 30.0, heartRateBpm = bpm, irregularity = irregular, seed = seed)

    private fun record(
        id: String,
        month: String,
        date: String,
        time: String,
        result: EcgResult,
        bpm: Int,
        symptoms: List<Symptom> = emptyList(),
        samples: FloatArray? = null,
    ) = EcgRecordUi(id, month, date, time, result, bpm, symptoms, 30, SyntheticEcg.SAMPLE_RATE_HZ, samples, samples?.let { metrics(it) })

    /** Real analysis of the sample waveform, with a fixed recording time (24 Sep 2026, 09:41:05 UTC). */
    private fun metrics(samples: FloatArray) = com.heartline.shared.ecg.EcgAnalyzer.analyze(
        samples,
        SyntheticEcg.SAMPLE_RATE_HZ,
        com.heartline.shared.ecg.EcgSession(startedAtMs = 1_790_242_865_000, endedAtMs = 1_790_242_897_000, leadOffSec = 1.5f, measuredRateHz = 499.6f),
    ).metrics

    val ecgRecords: List<EcgRecordUi> by lazy {
        listOf(
            record("e1", "September 2026", "Today", "9:41 AM", EcgResult.SINUS_RHYTHM, 72, samples = ecg(72.0)),
            record("e2", "September 2026", "Sep 21", "10:15 PM", EcgResult.AFIB_SIGNS, 94, listOf(Symptom.PALPITATIONS, Symptom.FATIGUE), ecg(94.0, 0.3, 2)),
            record("e3", "September 2026", "Sep 18", "7:02 AM", EcgResult.LOW_HEART_RATE, 47),
            record("e4", "September 2026", "Sep 12", "6:30 PM", EcgResult.SINUS_RHYTHM, 68),
            record("e5", "August 2026", "Aug 30", "8:12 PM", EcgResult.INCONCLUSIVE, 88, listOf(Symptom.DIZZINESS)),
            record("e6", "August 2026", "Aug 22", "1:47 PM", EcgResult.HIGH_HEART_RATE, 126),
            record("e7", "August 2026", "Aug 3", "9:05 AM", EcgResult.SINUS_RHYTHM, 70),
        )
    }

    val ecgList get() = EcgListState(ecgRecords, loading = false)

    val heartLimits = com.heartline.shared.hr.HeartLimits(
        restNormal = 58,
        sleepNormal = 51,
        restLow = 53,
        restHigh = 66,
        high = 90,
        low = 40,
        sleepLow = 36,
        exerciseMax = 185,
        restConfidence = 0.98,
        sleepConfidence = 0.95,
    )

    val heartRate: HeartRateUi by lazy {
        val random = kotlin.random.Random(4)
        val minutes = (0 until 9 * 60 + 41).map { m ->
            val hour = m / 60.0
            val base = if (hour < 6) 54.0 else 66.0 + 12 * kotlin.math.sin((hour - 6) / 16.0 * Math.PI)
            val avg = (base + random.nextDouble(-3.0, 3.0)).toInt()
            com.heartline.shared.hr.HrBuckets.Slot(m, avg - random.nextInt(1, 4), avg + random.nextInt(1, 6), avg)
        }
        val day = com.heartline.shared.hr.HrBuckets.of(minutes, 30)
        val week = (0 until 7).map { i -> com.heartline.shared.hr.RangeBucket(i, 48 + i % 3, 118 + (i * 7) % 20, 66) }
        HeartRateUi(
            latestBpm = 64,
            latestTime = "Today 9:41 AM",
            restingBpm = 56,
            minBpm = day.minOf { it.min },
            maxBpm = day.maxOf { it.max },
            day = day,
            week = week,
            weekDates = listOf("Thu 18 Sep", "Fri 19 Sep", "Sat 20 Sep", "Sun 21 Sep", "Mon 22 Sep", "Tue 23 Sep", "Wed 24 Sep"),
            hrvTodayMs = 34,
            hrvWeek = listOf(31f, 36f, 29f, null, 40f, 33f, 34f),
            weekLabels = listOf("T", "F", "S", "S", "M", "T", "W"),
            unreadAlerts = 1,
            sleepAvgBpm = 53,
            sleepMinBpm = 47,
            exerciseMinutes = 42,
            exercisePeakBpm = 164,
            maxHr = 185,
            zoneBounds = com.heartline.shared.hr.HeartBaseline.zones(185, 56),
            zoneMinutes = listOf(18, 9, 14, 12, 3),
            limits = heartLimits,
            nights = (0 until 28).map { i -> if (i == 9) null else (49 + (i * 7) % 5 + if (i >= 25) 6 else 0).toFloat() },
            restingWeek = listOf(57f, 56f, 58f, null, 55f, 56f, 56f),
            dayByActivity = mapOf(
                com.heartline.shared.hr.HrContext.REST to day,
                com.heartline.shared.hr.HrContext.SLEEP to day.filter { it.index < 12 },
            ),
        )
    }

    val bpHome: BpHomeUi by lazy {
        val values = listOf(118 to 76, 121 to 79, 116 to 74, 124 to 81, 119 to 77, 131 to 84, 117 to 75, 122 to 78, 120 to 77, 126 to 80)
        val readings = values.mapIndexed { i, (s, d) ->
            BpReadingUi("bp$i", if (i == 0) "Today" else "Sep ${24 - i}", "8:0$i AM", s, d, 64 + i % 4, uncertainty = 6 + i % 3)
        }
        BpHomeUi(
            calibrated = true,
            daysLeft = 21,
            latest = readings.first(),
            readings = readings,
            average7 = 121 to 78,
            average30 = 120 to 77,
            accuracy = com.heartline.shared.bp.BpAccuracy(5, 2.4, 6.1, 1.2, 4.3, 80),
            canValidateLatest = true,
        )
    }

    fun metricDetail(metric: Metric): MetricDetailUi {
        val rows = (0 until 8).map { i ->
            val date = if (i == 0) "Today" else "Sep ${24 - i}"
            when (metric) {
                Metric.SPO2 -> MetricReadingUi("s$i", date, "7:1$i AM", "${97 - i % 3}", "%", (97 - i % 3).toFloat(), listOf(R.string.detail_heart_rate to "6${i}  bpm"))
                Metric.SKIN_TEMPERATURE -> MetricReadingUi(
                    "t$i",
                    date,
                    "7:1$i AM",
                    listOf("+0.3", "-0.1", "+0.1", "0.0", "+0.2", "-0.2", "+0.1", "0.0")[i],
                    "°C",
                    33f + i % 3 * 0.2f,
                    listOf(R.string.detail_skin to "33.${4 + i % 3} °C", R.string.detail_ambient to "23.5 °C"),
                )
                Metric.BODY_COMPOSITION -> MetricReadingUi(
                    "b$i",
                    date,
                    "7:1$i AM",
                    "2${1 + i % 2}.${4 - i % 3}",
                    "%",
                    21f + i % 2,
                    listOf(R.string.detail_muscle to "29.8 kg", R.string.detail_water to "38.6 kg", R.string.detail_bmr to "1540 kcal"),
                )
                else -> MetricReadingUi("x$i", date, "7:1$i AM", "${32 + i * 4}", null, 32f + i * 4, listOf(R.string.detail_level to "Low", R.string.detail_hrv to "38 ms"))
            }
        }
        return MetricDetailUi(metric, rows)
    }

    /** Eight results over three months, newest first. */
    val body: BodyDetailUi = run {
        val day = 86_400_000L
        val now = 1_790_000_000_000L
        val entries = listOf(0, 9, 18, 30, 44, 58, 72, 88).mapIndexed { i, daysAgo ->
            val report = BodyComposition.report(com.heartline.phone.data.DemoData.demoBody(daysAgo), Sex.MALE, 34)
            BodyEntryUi("b$i", now - daysAgo * day, if (i == 0) "Today" else "Sep ${24 - i}", "7:1$i AM", report)
        }
        BodyDetailUi(entries, 76.4f)
    }

    val alerts = listOf(
        AlertUi("a1", AlertKind.IRREGULAR_RHYTHM, "Sep 21", "3:12 AM", 94, 5, read = false, ecgRegular = true),
        AlertUi("a0", AlertKind.HIGH_HEART_RATE, "Sep 23", "10:00 AM", 62, 0, read = false, threshold = 57, context = com.heartline.shared.hr.HrContext.SLEEP, normal = 51, trend = com.heartline.shared.hr.HeartTrend.ELEVATED_RESTING),
        AlertUi("a2", AlertKind.HIGH_HEART_RATE, "Sep 14", "11:40 PM", 104, 0, read = true, threshold = 90, context = com.heartline.shared.hr.HrContext.REST, normal = 58),
        AlertUi("a3", AlertKind.LOW_HEART_RATE, "Aug 30", "4:05 AM", 33, 0, read = true, threshold = 35, context = com.heartline.shared.hr.HrContext.SLEEP),
    )

    val home get() = HomeState(
        name = "Sara",
        watchName = "Galaxy Watch8 Classic",
        latestEcg = ecgRecords.first(),
        tiles = mapOf(
            Metric.BLOOD_PRESSURE to TileValue("118/76", "mmHg", "Today 8:05 AM", "Calibration valid · 21 days left"),
            Metric.HEART_RATE to TileValue("64", "bpm", "Now", "Resting 58 bpm today"),
            Metric.SPO2 to TileValue("97", "%", "Yesterday", "Average while measuring"),
            Metric.SKIN_TEMPERATURE to TileValue("+0.3", "°C", "Last night", "vs. your 7-day baseline"),
            Metric.BODY_COMPOSITION to TileValue("21.4", "%", "Sep 20", "Body fat"),
            Metric.STRESS to TileValue("Low", null, "Now"),
        ),
    )
}
