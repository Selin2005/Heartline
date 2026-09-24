package com.heartline.wear.sensor

import com.heartline.shared.hr.Hrv
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import android.util.Log
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/** Why a quick measurement is struggling; shown as a hint while measuring. */
enum class QuickHint { HOLD_STILL, LOW_SIGNAL, TOUCH_KEYS, WRIST_CONTACT }

sealed interface QuickEvent {
    data class Progress(val fraction: Float, val hint: QuickHint? = null) : QuickEvent

    data class Result(val summary: RecordSummary) : QuickEvent

    /** Live values while measuring: heart rate, and for stress the HRV so far (RMSSD, ms). */
    data class Live(val bpm: Int?, val hrvMs: Double? = null) : QuickEvent

    data class Failed(val problem: SensorProblem?, val hint: QuickHint? = null) : QuickEvent
}

/** An on-demand wellness measurement that ends with one [RecordSummary]. */
interface QuickSource {
    val metric: Metric
    val kind: RecordKind
    val seconds: Int

    fun measure(profile: UserProfile?): Flow<QuickEvent>
}

/**
 * Stress from one minute of resting HRV (HEART_RATE_CONTINUOUS IBIs), plus EDA when available.
 *
 * The minute is timed by the clock, not by counting samples: the tracker can pause or deliver in
 * bursts, and a sample count could then wait forever. No data at all within [noDataSeconds] ends
 * the measurement with a hint instead of a stuck progress ring.
 */
class StressSource(
    private val hr: HrSource,
    private val skinConductance: suspend () -> Float? = { null },
    override val seconds: Int = 60,
    private val tickMs: Long = 1_000,
    private val noDataSeconds: Int = 15,
) : QuickSource {
    override val metric = Metric.STRESS
    override val kind = RecordKind.STRESS

    override fun measure(profile: UserProfile?): Flow<QuickEvent> = channelFlow {
        val ibis = mutableListOf<Int>()
        var samples = 0
        var offBody = false
        val reader = launch {
            hr.stream().collect { sample ->
                samples++
                offBody = !sample.onBody
                if (!sample.onBody) return@collect
                ibis += sample.ibiMs
                if (sample.bpm > 0) send(QuickEvent.Live(sample.bpm, Hrv.compute(ibis)?.rmssdMs))
            }
        }
        for (second in 1..seconds) {
            delay(tickMs)
            if (second == noDataSeconds && samples == 0) {
                log("no heart-rate data after $noDataSeconds s")
                reader.cancel()
                send(QuickEvent.Failed(null, QuickHint.LOW_SIGNAL))
                return@channelFlow
            }
            send(QuickEvent.Progress(second.toFloat() / seconds, if (offBody) QuickHint.WRIST_CONTACT else null))
        }
        reader.cancel()
        val hrv = Hrv.compute(ibis)
        log("done: samples=$samples ibis=${ibis.size} clean=${Hrv.clean(ibis).size} rmssd=${hrv?.rmssdMs}")
        if (hrv == null) {
            send(QuickEvent.Failed(null, QuickHint.LOW_SIGNAL))
        } else {
            val eda = withTimeoutOrNull(EDA_TIMEOUT_MS) { skinConductance() }
            send(QuickEvent.Result(RecordSummary.Stress(StressIndex.score(hrv.rmssdMs, eda), hrv.rmssdMs, eda)))
        }
    }

    private fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private companion object {
        const val TAG = "Heartline/Stress"
        const val EDA_TIMEOUT_MS = 15_000L
    }
}

/** Plays a plausible measurement for development without sensors. */
class FakeQuickSource(
    override val metric: Metric,
    override val seconds: Int,
    private val tickMs: Long = 250,
    private val result: (UserProfile?) -> RecordSummary,
) : QuickSource {
    override val kind = RecordKind.entries.first { it.metric == metric }

    override fun measure(profile: UserProfile?): Flow<QuickEvent> = flow {
        // Four progress updates per simulated second; [tickMs] sets the real pace (250 ms = real time).
        val ticks = seconds * 4
        for (i in 1..ticks) {
            delay(tickMs)
            emit(QuickEvent.Progress(i.toFloat() / ticks, if (i in ticks / 3 until ticks / 3 + 2) QuickHint.HOLD_STILL else null))
            if (i % 4 == 0 && metric != Metric.SKIN_TEMPERATURE) emit(QuickEvent.Live(66 + (i / 4) % 5, if (metric == Metric.STRESS) 38.0 + i % 7 else null))
        }
        emit(QuickEvent.Result(result(profile)))
    }

    companion object {
        private val random = Random(5)

        fun all(tickMs: Long = 250): List<QuickSource> = listOf(
            FakeQuickSource(Metric.SPO2, 30, tickMs) { RecordSummary.Spo2(96 + random.nextInt(0, 3), 64, lowConfidence = false) },
            FakeQuickSource(Metric.SKIN_TEMPERATURE, 10, tickMs) { RecordSummary.SkinTemperature(33.2f + random.nextFloat(), 24.5f) },
            FakeQuickSource(Metric.BODY_COMPOSITION, 15, tickMs) { profile ->
                val weight = profile?.weightKg ?: 70f
                RecordSummary.BodyComposition(21.4f, weight * 0.42f, weight * 0.55f, 1520)
            },
        )
    }
}
