package com.heartline.shared.ecg

import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.model.EcgResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wellness rhythm classification with SHM's categories (MASTER_PLAN §5):
 * quality gate → heart-rate bounds (<50 low, >120 high) → irregular + absent P waves → AFib signs;
 * regular with P waves at 50–100 bpm → sinus rhythm; anything else is inconclusive.
 */
object RhythmClassifier {
    const val LOW_HR = 50
    const val HIGH_HR = 120
    const val SINUS_MAX_HR = 100
    const val MAX_LEAD_OFF_RATIO = 0.3f

    data class Evidence(val quality: Double, val features: RrFeatures?, val pWaveMv: Double?)

    fun classify(clean: FloatArray, peaks: IntArray, fs: Int, bpm: Int?, leadOffRatio: Float): Pair<EcgResult, Evidence> {
        val quality = SignalQuality.score(clean, peaks, fs)
        val features = RrFeatures.of(RPeakDetector.rrIntervalsMs(peaks, fs))
        val pWave = pWaveAmplitude(clean, peaks, fs)
        val evidence = Evidence(quality, features, pWave)
        val result = when {
            leadOffRatio > MAX_LEAD_OFF_RATIO || bpm == null || features == null -> EcgResult.POOR_RECORDING
            quality < SignalQuality.MIN_GOOD -> EcgResult.POOR_RECORDING
            bpm > HIGH_HR -> EcgResult.HIGH_HEART_RATE
            bpm < LOW_HR -> EcgResult.LOW_HEART_RATE
            features.isIrregular && (pWave == null || pWave < P_WAVE_PRESENT_MV || features.nRmssd > 0.15) -> EcgResult.AFIB_SIGNS
            features.isRegular && bpm <= SINUS_MAX_HR && pWave != null && pWave >= P_WAVE_PRESENT_MV -> EcgResult.SINUS_RHYTHM
            else -> EcgResult.INCONCLUSIVE
        }
        return result to evidence
    }

    private const val P_WAVE_PRESENT_MV = 0.05

    /**
     * Peak-to-baseline amplitude of the averaged P wave: beats are aligned on R, the median beat
     * is formed, and the largest deflection 250–80 ms before R is measured against the PR baseline.
     */
    fun pWaveAmplitude(clean: FloatArray, peaks: IntArray, fs: Int): Double? {
        val pre = (0.30 * fs).toInt()
        val usable = peaks.filter { it - pre >= 0 }
        if (usable.size < 5) return null
        val template = DoubleArray(pre) { k -> median(usable.map { clean[it - pre + k].toDouble() }) }
        val baseline = median((pre - (0.08 * fs).toInt() until pre - (0.04 * fs).toInt()).map { template[it] })
        val from = pre - (0.25 * fs).toInt()
        val to = pre - (0.08 * fs).toInt()
        return (from until to).maxOf { abs(template[it] - baseline) }
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/** 0..1 recording quality from QRS prominence and R-amplitude consistency. */
object SignalQuality {
    const val MIN_GOOD = 0.45

    fun score(clean: FloatArray, peaks: IntArray, fs: Int): Double {
        if (peaks.size < 5 || clean.isEmpty()) return 0.0
        val mean = clean.average()
        val sd = sqrt(clean.sumOf { (it - mean) * (it - mean) } / clean.size)
        if (sd < 1e-6) return 0.0
        // Kurtosis: sharp QRS complexes on a quiet baseline give high values; noise is ~3.
        val kurtosis = clean.sumOf {
            val z = (it - mean) / sd
            z * z * z * z
        } / clean.size
        val amps = peaks.map { abs(clean[it] - mean) }
        val ampMean = amps.average()
        val ampCov = sqrt(amps.sumOf { (it - ampMean) * (it - ampMean) } / amps.size) / ampMean
        val kurtosisScore = ((kurtosis - 3.0) / 7.0).coerceIn(0.0, 1.0)
        val consistency = (1.0 - ampCov / 0.5).coerceIn(0.0, 1.0)
        return 0.6 * kurtosisScore + 0.4 * consistency
    }
}
