package com.heartline.phone.data

import com.heartline.shared.hr.AlertKind
import com.heartline.shared.hr.HealthAlert
import com.heartline.shared.hr.HrBatch
import com.heartline.shared.hr.HrMinute
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordMeta
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.model.Symptom
import com.heartline.shared.sample.SyntheticEcg
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic records for debug builds and tests (never shipped as real data). */
object DemoData {
    private const val DAY = 24 * 60 * 60 * 1000L

    fun ecgRecords(now: Long): List<Pair<RecordMeta, FloatArray>> = listOf(
        ecg("demo-1", now - 2 * 60 * 60 * 1000L, 72.0, 0.02, EcgResult.SINUS_RHYTHM, emptyList(), 1),
        ecg("demo-2", now - 3 * DAY, 94.0, 0.3, EcgResult.AFIB_SIGNS, listOf(Symptom.PALPITATIONS, Symptom.FATIGUE), 2),
        ecg("demo-3", now - 6 * DAY, 47.0, 0.02, EcgResult.LOW_HEART_RATE, emptyList(), 3),
        ecg("demo-4", now - 12 * DAY, 68.0, 0.02, EcgResult.SINUS_RHYTHM, emptyList(), 4),
        ecg("demo-5", now - 25 * DAY, 88.0, 0.12, EcgResult.INCONCLUSIVE, listOf(Symptom.DIZZINESS), 5),
        ecg("demo-6", now - 33 * DAY, 126.0, 0.02, EcgResult.HIGH_HEART_RATE, emptyList(), 6),
    )

    private fun ecg(
        id: String,
        at: Long,
        bpm: Double,
        irregular: Double,
        result: EcgResult,
        symptoms: List<Symptom>,
        seed: Int,
    ): Pair<RecordMeta, FloatArray> {
        val wave = SyntheticEcg.generate(30.0, bpm, irregular, seed = seed)
        val meta = RecordMeta(
            id,
            RecordKind.ECG,
            at,
            30_000,
            SyntheticEcg.SAMPLE_RATE_HZ,
            wave.size,
            RecordSummary.Ecg(bpm.toInt(), result, 0f, symptoms),
        )
        return meta to wave
    }

    /** A week of per-minute heart rate with a daily rhythm, plus one example alert. */
    fun heartBatch(now: Long): HrBatch {
        val random = Random(11)
        val minutes = mutableListOf<HrMinute>()
        val start = (now - 6 * DAY) / 60_000 * 60_000
        var t = start
        while (t <= now) {
            val hourOfDay = ((t / 3_600_000) % 24).toInt()
            val asleep = hourOfDay < 6
            val base = if (asleep) 54.0 else 68.0 + 10 * sin((hourOfDay - 6) / 16.0 * PI)
            val avg = (base + random.nextDouble(-4.0, 4.0)).toInt()
            minutes += HrMinute(t, avg, avg - random.nextInt(2, 7), avg + random.nextInt(2, 9), 28.0 + random.nextDouble(-8.0, 12.0), resting = asleep || random.nextDouble() > 0.2)
            t += 5 * 60_000L
        }
        return HrBatch("demo-hr", minutes)
    }

    fun alert(now: Long) = HealthAlert("demo-alert", AlertKind.IRREGULAR_RHYTHM, now - 3 * DAY + 3_600_000L, 94, List(5) { now - 3 * DAY - it * 900_000L })

    fun bpReadings(now: Long): List<RecordMeta> {
        val random = Random(21)
        return (0 until 14).map { i ->
            val sys = 118 + random.nextInt(-7, 9)
            val dia = 77 + random.nextInt(-5, 6)
            RecordMeta("demo-bp-$i", RecordKind.BLOOD_PRESSURE, now - i * DAY - 3_600_000L, 20_000, 0, 0, RecordSummary.BloodPressure(sys, dia, 64 + random.nextInt(-5, 6)))
        }
    }

    /** A couple of weeks of SpO2, skin temperature, body composition and stress. */
    fun wellnessRecords(now: Long): List<RecordMeta> {
        val random = Random(31)
        return (0 until 10).flatMap { i ->
            val at = now - i * DAY - 5 * 3_600_000L
            listOf(
                RecordMeta("demo-spo2-$i", RecordKind.SPO2, at, 30_000, 0, 0, RecordSummary.Spo2(95 + random.nextInt(0, 4), 60 + random.nextInt(0, 10), false)),
                RecordMeta("demo-temp-$i", RecordKind.SKIN_TEMPERATURE, at + 60_000, 10_000, 0, 0, RecordSummary.SkinTemperature(33.0f + random.nextFloat() * 0.8f, 23.5f)),
                RecordMeta("demo-stress-$i", RecordKind.STRESS, at + 120_000, 60_000, 0, 0, RecordSummary.Stress(20 + random.nextInt(0, 50), 25.0 + random.nextInt(0, 30), null)),
            ) + if (i % 3 == 0) {
                listOf(RecordMeta("demo-body-$i", RecordKind.BODY_COMPOSITION, at + 180_000, 15_000, 0, 0, demoBody(i)))
            } else {
                emptyList()
            }
        }
    }

    /** A body composition result [daysAgo] days back: slowly losing fat and gaining a little muscle. */
    fun demoBody(daysAgo: Int): RecordSummary.BodyComposition {
        val weight = 76.4f + daysAgo * 0.06f
        val fat = 21.4f + daysAgo * 0.035f
        val muscle = 33.8f - daysAgo * 0.008f
        return RecordSummary.BodyComposition(
            bodyFatPercent = fat,
            skeletalMuscleKg = muscle,
            bodyWaterKg = weight * 0.565f,
            bmrKcal = (1650 - daysAgo * 0.4f).toInt(),
            weightKg = weight,
            heightCm = 178f,
            bodyFatMassKg = weight * fat / 100f,
            skeletalMusclePercent = 100f * muscle / weight,
            fatFreeMassKg = weight * (1 - fat / 100f),
            fatFreePercent = 100f - fat,
            impedanceOhm = 486f + daysAgo * 0.3f,
            phaseAngleDeg = -6.6f,
        )
    }

    suspend fun seedIfEmpty(
        repository: RecordRepository,
        heart: HeartRepository? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!repository.isEmpty()) return
        ecgRecords(now).forEach { (meta, wave) -> repository.save(meta, wave) }
        bpReadings(now).forEach { repository.save(it, null) }
        wellnessRecords(now).forEach { repository.save(it, null) }
        // No demo BP calibration: a synthetic one would be sent to the watch and pin every reading.
        heart?.saveBatch(heartBatch(now))
        heart?.saveAlert(alert(now))
    }
}
