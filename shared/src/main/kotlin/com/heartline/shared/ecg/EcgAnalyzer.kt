package com.heartline.shared.ecg

import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import kotlin.math.roundToInt

data class EcgAnalysis(
    val peaks: IntArray,
    val averageBpm: Int?,
    val result: EcgResult,
    val evidence: RhythmClassifier.Evidence,
    val metrics: EcgMetrics
)

/** Timing and contact facts the recorder knows and the signal doesn't. */
data class EcgSession(
    val startedAtMs: Long = 0,
    val endedAtMs: Long = 0,
    val leadOffSec: Float = 0f,
    val leadOffRatio: Float = 0f,
    val measuredRateHz: Float? = null
)

/**
 * Turns a finished recording into a result plus [EcgMetrics]:
 * clean (0.5–40 Hz, mains notch) → R peaks → per-second quality (motion / weak contact) →
 * per-beat template match and noise → intervals from trustworthy consecutive beats only →
 * heart rate, HRV and the rhythm class. A recording is "poor" only when there isn't enough
 * clean signal, and the reason is recorded.
 */
object EcgAnalyzer {
    const val MIN_USABLE_SEC = 12f
    const val MIN_VALID_BEATS = 8
    const val MIN_DURATION_SEC = 20f
    const val MAX_LEAD_OFF_RATIO = 0.5f

    /** Kept for callers that only know the lead-off ratio. */
    fun analyze(raw: FloatArray, fs: Int, leadOffRatio: Float): EcgAnalysis = analyze(raw, fs, EcgSession(leadOffRatio = leadOffRatio))

    fun analyze(raw: FloatArray, fs: Int, session: EcgSession): EcgAnalysis {
        val signal = sanitize(raw)
        val clean = EcgFilter.clean(signal, fs)
        val peaks = RPeakDetector.detect(clean, fs)
        val beats = BeatTemplate.assess(clean, peaks, fs)
        val noisyBeat = BooleanArray(peaks.size) {
            beats.correlation[it] < BeatTemplate.MIN_CORRELATION ||
                beats.noiseRatio[it] > BeatTemplate.MAX_NOISE_RATIO
        }
        val seconds = EcgQuality.withBeatNoise(EcgQuality.seconds(clean, fs), peaks, noisyBeat, fs)
        val goodSecond = { index: Int -> seconds.getOrNull(index / fs)?.let { it == SecondKind.GOOD } ?: true }
        val valid = BooleanArray(peaks.size) { !noisyBeat[it] && goodSecond(peaks[it]) }

        // Intervals only between two consecutive trustworthy beats.
        val rr = (1 until peaks.size).filter { valid[it] && valid[it - 1] }
            .map { (peaks[it] - peaks[it - 1]) * 1000.0 / fs }
            .filter { it in 250.0..2500.0 }
        val features = RrFeatures.of(rr)
        val averageBpm = rr.takeIf { it.size >= 3 }?.sorted()?.let { (60_000 / it[it.size / 2]).roundToInt() }
        val (minBpm, maxBpm) = hrRange(rr)

        val duration = signal.size.toFloat() / fs
        val count = { kind: SecondKind -> seconds.count { it == kind }.toFloat() }
        val usable =
            count(SecondKind.GOOD) +
                (duration - seconds.size).coerceAtLeast(0f).let { tail -> if (seconds.lastOrNull() == SecondKind.GOOD) tail else 0f }
        val motion = count(SecondKind.MOTION)
        val muscle = count(SecondKind.MUSCLE)
        val flat = count(SecondKind.FLAT)
        val validBeats = valid.count { it }
        val validFraction = if (peaks.isEmpty()) 0.0 else validBeats.toDouble() / peaks.size
        val quality = (100 * (0.6 * (usable / duration.coerceAtLeast(1f)) + 0.4 * validFraction)).roundToInt().coerceIn(0, 100)

        val reason = when {
            duration < MIN_DURATION_SEC -> EcgPoorReason.TOO_SHORT
            session.leadOffRatio > MAX_LEAD_OFF_RATIO -> EcgPoorReason.LEAD_OFF
            seconds.isNotEmpty() && flat == seconds.size.toFloat() -> EcgPoorReason.LOW_AMPLITUDE
            usable < MIN_USABLE_SEC -> listOf(
                EcgPoorReason.MOTION to motion,
                EcgPoorReason.MUSCLE_NOISE to muscle,
                EcgPoorReason.LOW_AMPLITUDE to flat
            ).maxBy { it.second }.first
            validBeats < MIN_VALID_BEATS || averageBpm == null || features == null -> EcgPoorReason.TOO_FEW_BEATS
            else -> EcgPoorReason.NONE
        }

        val validPeaks = peaks.filterIndexed { i, _ -> valid[i] }.toIntArray()
        val pWave = RhythmClassifier.pWaveAmplitude(clean, validPeaks, fs)
        val pRelative = if (pWave != null && beats.templateRangeMv > 0) pWave / beats.templateRangeMv else null
        val evidence = RhythmClassifier.Evidence(quality / 100.0, features, pWave, pRelative)
        val result = RhythmClassifier.classify(reason, averageBpm, features, pRelative)

        val metrics = EcgMetrics(
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            durationSec = duration,
            usableSec = usable,
            noiseSec = (duration - usable).coerceAtLeast(0f),
            motionSec = motion,
            muscleNoiseSec = muscle,
            leadOffSec = session.leadOffSec,
            averageBpm = averageBpm,
            minBpm = minBpm,
            maxBpm = maxBpm,
            beats = peaks.size,
            meanRrMs = features?.meanMs?.roundToInt(),
            sdnnMs = features?.sdnnMs?.roundToInt(),
            rmssdMs = features?.rmssdMs?.roundToInt(),
            qualityScore = quality,
            poorReason = reason,
            noisySeconds = seconds.indices.filter { seconds[it] != SecondKind.GOOD },
            sampleRateHz = session.measuredRateHz ?: fs.toFloat(),
            inverted = beats.inverted
        )
        return EcgAnalysis(peaks, averageBpm, result, evidence, metrics)
    }

    /**
     * Lowest and highest heart rate over 3-beat averages (a single early/late beat doesn't
     * set the range), after dropping intervals far from the median.
     */
    fun hrRange(rr: List<Double>): Pair<Int?, Int?> {
        if (rr.size < 3) return null to null
        val median = rr.sorted()[rr.size / 2]
        val kept = rr.filter { it in median * 0.6..median * 1.6 }
        if (kept.size < 3) return null to null
        val rates = kept.windowed(3) { w -> 60_000 / w.average() }
        return rates.min().roundToInt() to rates.max().roundToInt()
    }

    /** Non-finite samples (a dropped value) would poison every filter: hold the last good value. */
    private fun sanitize(raw: FloatArray): FloatArray {
        var last = raw.firstOrNull { it.isFinite() } ?: 0f
        return FloatArray(raw.size) { i ->
            val v = raw[i]
            if (v.isFinite()) v.also { last = it } else last
        }
    }
}
