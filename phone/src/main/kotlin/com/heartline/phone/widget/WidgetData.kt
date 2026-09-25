package com.heartline.phone.widget

import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.HeartRepository
import com.heartline.phone.data.HrMinuteEntity
import com.heartline.phone.data.RecordRepository
import com.heartline.phone.data.StoredRecord
import com.heartline.phone.ui.model.HeartSummaries
import com.heartline.phone.ui.model.MetricFormat
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.bp.BpCategory
import com.heartline.shared.hr.HrBuckets
import com.heartline.shared.hr.RangeBucket
import com.heartline.shared.model.EcgResult
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.StressLevel
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/** Everything the home-screen widgets show, built once per update from the app's own data. */
data class WidgetSnapshot(
    val heartRate: HeartRate? = null,
    val ecg: Ecg? = null,
    val bp: Bp? = null,
    val calibration: Calibration = Calibration.None,
    val spo2: Reading? = null,
    val temperature: Reading? = null,
    val body: Reading? = null,
    val stress: Stress? = null,
) {
    /** [day]: today's 30-minute min–max buckets (index 0..47). */
    data class HeartRate(
        val bpm: Int,
        val at: String,
        val resting: Int?,
        val min: Int?,
        val max: Int?,
        val day: List<RangeBucket>,
    ) {
        /** The last six hours of [day], re-indexed 0..11 for the small chart. */
        fun recent(nowSlot: Int): List<RangeBucket> =
            day.filter { it.index in nowSlot - 11..nowSlot }.map { it.copy(index = it.index - (nowSlot - 11)) }
    }

    data class Ecg(val result: EcgResult, val at: String, val bpm: Int?)

    data class Bp(val systolic: Int, val diastolic: Int, val category: BpCategory, val at: String)

    data class Reading(val value: String, val unit: String?, val at: String)

    data class Stress(val score: Int, val level: StressLevel, val hrvMs: Int?, val at: String)

    sealed interface Calibration {
        data object None : Calibration

        data class Valid(val daysLeft: Int) : Calibration

        data object Expired : Calibration
    }
}

/** Pure assembly from repository rows, so it can be tested without a database. */
object WidgetSnapshots {
    fun build(
        records: Map<RecordKind, List<StoredRecord>>,
        todaysMinutes: List<HrMinuteEntity>,
        calibration: BpCalibration?,
        formatter: RecordFormatter,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WidgetSnapshot {
        fun at(ms: Long) = "${formatter.date(ms)} ${formatter.time(ms)}"
        fun reading(kind: RecordKind) = records[kind].orEmpty().let { list ->
            MetricFormat.readings(list.take(8), formatter).firstOrNull()?.let { WidgetSnapshot.Reading(it.value, it.unit, "${it.date} ${it.time}") }
        }
        val latestMinute = todaysMinutes.maxByOrNull { it.minuteStartMs }
        val heartRate = latestMinute?.let { latest ->
            WidgetSnapshot.HeartRate(
                bpm = latest.avgBpm,
                at = at(latest.minuteStartMs),
                resting = HeartSummaries.resting(todaysMinutes),
                min = todaysMinutes.minOf { it.minBpm },
                max = todaysMinutes.maxOf { it.maxBpm },
                day = HrBuckets.of(
                    todaysMinutes.map {
                        val local = Instant.ofEpochMilli(it.minuteStartMs).atZone(zone)
                        HrBuckets.Slot(local.hour * 60 + local.minute, it.minBpm, it.maxBpm, it.avgBpm)
                    },
                    30,
                ),
            )
        }
        val ecg = records[RecordKind.ECG].orEmpty().firstOrNull()?.let { r ->
            val s = r.summary as RecordSummary.Ecg
            s.result?.let { WidgetSnapshot.Ecg(it, at(r.entity.startedAtMs), s.averageBpm) }
        }
        val bp = records[RecordKind.BLOOD_PRESSURE].orEmpty().firstOrNull()?.let { r ->
            val s = r.summary as RecordSummary.BloodPressure
            WidgetSnapshot.Bp(s.systolic, s.diastolic, BpCategory.of(s.systolic, s.diastolic), at(r.entity.startedAtMs))
        }
        val stress = records[RecordKind.STRESS].orEmpty().firstOrNull()?.let { r ->
            val s = r.summary as RecordSummary.Stress
            WidgetSnapshot.Stress(s.score, StressIndex.level(s.score), s.rmssdMs?.toInt(), at(r.entity.startedAtMs))
        }
        return WidgetSnapshot(
            heartRate = heartRate,
            ecg = ecg,
            bp = bp,
            calibration = when {
                calibration == null -> WidgetSnapshot.Calibration.None
                calibration.isValid(nowMs) -> WidgetSnapshot.Calibration.Valid(calibration.daysLeft(nowMs))
                else -> WidgetSnapshot.Calibration.Expired
            },
            spo2 = reading(RecordKind.SPO2),
            temperature = reading(RecordKind.SKIN_TEMPERATURE),
            body = reading(RecordKind.BODY_COMPOSITION),
            stress = stress,
        )
    }
}

/** Reads the repositories for [WidgetSnapshots.build]. */
class WidgetDataSource(
    private val records: RecordRepository,
    private val heart: HeartRepository,
    private val bp: BpRepository,
    private val formatter: () -> RecordFormatter,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun load(): WidgetSnapshot {
        val nowMs = now()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone()).toLocalDate()
        val start = today.atStartOfDay(zone()).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val kinds = listOf(
            RecordKind.ECG,
            RecordKind.BLOOD_PRESSURE,
            RecordKind.SPO2,
            RecordKind.SKIN_TEMPERATURE,
            RecordKind.BODY_COMPOSITION,
            RecordKind.STRESS,
        )
        return WidgetSnapshots.build(
            kinds.associateWith { records.observe(it).first() },
            heart.minutes(start, end).first(),
            bp.calibration.first(),
            formatter(),
            nowMs,
            zone(),
        )
    }

    /** Minute of the day now, for [WidgetSnapshot.HeartRate.recent]. */
    fun nowSlot(): Int {
        val local = Instant.ofEpochMilli(now()).atZone(zone())
        return (local.hour * 60 + local.minute) / 30
    }
}
