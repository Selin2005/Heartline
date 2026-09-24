package com.heartline.wear.sensor

import com.heartline.shared.hr.Hrv
import com.heartline.shared.model.Metric
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.StressIndex
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlin.random.Random

/** Why a quick measurement is struggling; shown as a hint while measuring. */
enum class QuickHint { HOLD_STILL, LOW_SIGNAL, TOUCH_KEYS, WRIST_CONTACT }

sealed interface QuickEvent {
    data class Progress(val fraction: Float, val hint: QuickHint? = null) : QuickEvent

    data class Result(val summary: RecordSummary) : QuickEvent

    data class Failed(val problem: SensorProblem?, val hint: QuickHint? = null) : QuickEvent
}

/** An on-demand wellness measurement that ends with one [RecordSummary]. */
interface QuickSource {
    val metric: Metric
    val kind: RecordKind
    val seconds: Int

    fun measure(profile: UserProfile?): Flow<QuickEvent>
}

/** Stress from one minute of resting HRV (HEART_RATE_CONTINUOUS IBIs), plus EDA when available. */
class StressSource(
    private val hr: HrSource,
    private val skinConductance: suspend () -> Float? = { null },
    override val seconds: Int = 60,
) : QuickSource {
    override val metric = Metric.STRESS
    override val kind = RecordKind.STRESS

    override fun measure(profile: UserProfile?): Flow<QuickEvent> = flow {
        val ibis = mutableListOf<Int>()
        var n = 0
        var error: Throwable? = null
        hr.stream()
            .catch { error = it }
            .takeWhile { n < seconds }
            .collect { sample ->
                n++
                ibis += sample.ibiMs
                emit(QuickEvent.Progress(n.toFloat() / seconds, if (!sample.onBody) QuickHint.WRIST_CONTACT else null))
            }
        error?.let {
            emit(QuickEvent.Failed((it as? SensorException)?.problem))
            return@flow
        }
        val hrv = Hrv.compute(ibis)
        if (hrv == null) {
            emit(QuickEvent.Failed(null, QuickHint.LOW_SIGNAL))
        } else {
            val eda = skinConductance()
            emit(QuickEvent.Result(RecordSummary.Stress(StressIndex.score(hrv.rmssdMs, eda), hrv.rmssdMs, eda)))
        }
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
