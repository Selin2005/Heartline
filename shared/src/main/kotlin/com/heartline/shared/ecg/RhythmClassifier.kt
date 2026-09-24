package com.heartline.shared.ecg

import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import kotlin.math.abs

/**
 * Wellness rhythm classification with SHM's categories (MASTER_PLAN §5):
 * quality gate → heart-rate bounds (<50 low, >120 high) → irregular + absent P waves → AFib signs;
 * regular with P waves at 50–100 bpm → sinus rhythm; anything else is inconclusive.
 */
object RhythmClassifier {
    const val LOW_HR = 50
    const val HIGH_HR = 120
    const val SINUS_MAX_HR = 100

    /** P wave height relative to the whole beat (typically 0.1–0.2 in lead I; absent in AFib). */
    const val P_WAVE_PRESENT_RATIO = 0.04

    data class Evidence(val quality: Double, val features: RrFeatures?, val pWaveMv: Double?, val pWaveRatio: Double? = null)

    /**
     * Heart-rate bounds first, then rhythm: irregular without P waves → AFib signs; regular with
     * P waves up to 100 bpm → sinus rhythm. The P-wave test is relative to the beat, so it works
     * at wrist-ECG amplitudes (R often 0.3–0.6 mV) as well as chest-lead ones.
     */
    fun classify(reason: EcgPoorReason, bpm: Int?, features: RrFeatures?, pWaveRatio: Double?): EcgResult = when {
        reason != EcgPoorReason.NONE || bpm == null || features == null -> EcgResult.POOR_RECORDING
        bpm > HIGH_HR -> EcgResult.HIGH_HEART_RATE
        bpm < LOW_HR -> EcgResult.LOW_HEART_RATE
        features.isIrregular && (pWaveRatio == null || pWaveRatio < P_WAVE_PRESENT_RATIO || features.nRmssd > 0.15) -> EcgResult.AFIB_SIGNS
        features.isRegular && bpm <= SINUS_MAX_HR && pWaveRatio != null && pWaveRatio >= P_WAVE_PRESENT_RATIO -> EcgResult.SINUS_RHYTHM
        else -> EcgResult.INCONCLUSIVE
    }

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
