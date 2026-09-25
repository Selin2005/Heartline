package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.ecg.RPeakDetector

/** Median pulse arrival time and how many beats it rests on. [spreadMs]: interquartile range. */
data class PulseArrivalTime(val medianMs: Double, val beats: Int, val spreadMs: Double)

/**
 * Pulse arrival time (PAT): ECG R peak → the PPG pulse's steepest upstroke at the wrist.
 *
 * PAT shortens as pressure rises (pulse wave velocity grows with pressure); it tracks systolic
 * changes better than pulse shape alone, though it includes the pre-ejection period
 * (Mukkamala et al. 2015). The Galaxy Watch reports a green PPG sample with every ECG sample
 * (EcgSet.PPG_GREEN), so both come on one clock at 500 Hz. Whether that channel carries a full
 * pulse wave on every model is being verified on devices (docs/DEVICE_TESTING.md); until then PAT
 * is logged and stored, not yet used in the estimate.
 */
object PulseArrival {
    /** Plausible R → upstroke window at the wrist, ms. */
    const val MIN_MS = 120.0
    const val MAX_MS = 450.0
    const val MIN_BEATS = 8

    fun compute(ecg: FloatArray, ppg: FloatArray, fs: Int): PulseArrivalTime? {
        if (ecg.size != ppg.size || ecg.size < fs * 8) return null
        val peaks = RPeakDetector.detect(ecg, fs)
        if (peaks.size < MIN_BEATS) return null
        val filtered = ppg.filtFilt(Biquad.highPass(0.5, fs.toDouble()), Biquad.lowPass(8.0, fs.toDouble()))
        if (filtered.max() - filtered.min() < 1e-6f) return null
        val x = if (PpgFeatures.isInverted(filtered)) FloatArray(filtered.size) { -filtered[it] } else filtered
        val slope = FloatArray(x.size) { i -> if (i in 1 until x.size - 1) (x[i + 1] - x[i - 1]) / 2f else 0f }
        val from = (MIN_MS * fs / 1000).toInt()
        val to = (MAX_MS * fs / 1000).toInt()
        val pats = peaks.toList().zipWithNext().mapNotNull { (r, next) ->
            val end = minOf(r + to, next + from, x.size - 1)
            if (r + from >= end) return@mapNotNull null
            val steepest = (r + from..end).maxBy { slope[it] }
            // An edge of the window is not a real upstroke.
            if (steepest == r + from || steepest == end || slope[steepest] <= 0f) null else (steepest - r) * 1000.0 / fs
        }
        if (pats.size < MIN_BEATS) return null
        val sorted = pats.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        return PulseArrivalTime(sorted[sorted.size / 2], pats.size, q3 - q1)
    }
}
