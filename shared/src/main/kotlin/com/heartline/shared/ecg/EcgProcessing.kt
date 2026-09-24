package com.heartline.shared.ecg

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cleans a raw single-lead ECG for display and analysis (MASTER_PLAN §5). */
object EcgFilter {
    fun clean(samples: FloatArray, fs: Int, mainsHz: List<Double> = listOf(50.0, 60.0)): FloatArray {
        if (samples.size < fs) return samples.copyOf()
        val f = fs.toDouble()
        val stages = buildList {
            add(Biquad.highPass(0.5, f))
            add(Biquad.lowPass(40.0, f))
            mainsHz.filter { it < f / 2 }.forEach { add(Biquad.notch(it, f)) }
        }
        // Mirror-pad 1 s at each end so the filters don't ring at the edges. Even (not odd)
        // reflection: a recording that stops mid-QRS would otherwise gain a huge inverted spike.
        val pad = minOf(fs, samples.size - 1)
        val padded = FloatArray(samples.size + 2 * pad)
        for (i in 0 until pad) {
            padded[i] = samples[pad - i]
            padded[padded.size - 1 - i] = samples[samples.size - 1 - (pad - i)]
        }
        samples.copyInto(padded, pad)
        return padded.filtFilt(*stages.toTypedArray()).copyOfRange(pad, pad + samples.size)
    }
}

/**
 * QRS detector after Pan & Tompkins (1985): 5–15 Hz band-pass, derivative, squaring, 150 ms
 * moving-window integration, adaptive signal/noise thresholds with a 200 ms refractory period
 * and search-back. Returns sample indices of R peaks in [signal].
 */
object RPeakDetector {
    fun detect(signal: FloatArray, fs: Int): IntArray {
        if (signal.size < fs * 2) return IntArray(0)
        val f = fs.toDouble()
        val band = signal.filtFilt(Biquad.highPass(5.0, f), Biquad.lowPass(15.0, f))

        // Five-point derivative, squared.
        val sq = FloatArray(band.size)
        for (i in 2 until band.size - 2) {
            val d = (-band[i - 2] - 2 * band[i - 1] + 2 * band[i + 1] + band[i + 2]) / 8f
            sq[i] = d * d
        }
        // Moving-window integration.
        val window = (0.150 * fs).roundToInt()
        val mwi = FloatArray(sq.size)
        var acc = 0.0
        for (i in sq.indices) {
            acc += sq[i]
            if (i >= window) acc -= sq[i - window]
            mwi[i] = (acc / window).toFloat()
        }

        val refractory = (0.200 * fs).roundToInt()
        // Initialise thresholds from the median per-second maximum of the first 8 s. The plain
        // maximum (textbook) lets one artefact, e.g. the electrode settling spike when the finger
        // lands on the key, lift the threshold above every real beat.
        val learn = minOf(mwi.size / fs, 8)
        val secondMax = (0 until learn).map { w -> (w * fs until (w + 1) * fs).maxOf { mwi[it] }.toDouble() }.sorted()
        var spk = secondMax[secondMax.size / 2] * 0.5
        var npk = mwi.copyOfRange(0, learn * fs).average() * 0.5
        var threshold = npk + 0.25 * (spk - npk)

        val candidates = localMaxima(mwi, refractory / 2)
        val peaks = mutableListOf<Int>()
        val rr = ArrayDeque<Int>()
        for (c in candidates) {
            val v = mwi[c]
            if (peaks.isNotEmpty() && c - peaks.last() < refractory) continue
            // Search-back for a missed beat when the gap is > 1.66 × mean RR.
            if (peaks.isNotEmpty() && rr.size >= 2) {
                val meanRr = rr.average()
                if (c - peaks.last() > 1.66 * meanRr) {
                    val from = peaks.last() + refractory
                    val missed = (from until c - refractory).maxByOrNull { mwi[it] }
                    if (missed != null && mwi[missed] > threshold * 0.5) {
                        addPeak(peaks, rr, missed)
                        spk = 0.25 * mwi[missed] + 0.75 * spk
                    }
                }
            }
            if (v > threshold) {
                addPeak(peaks, rr, c)
                spk = 0.125 * v + 0.875 * spk
            } else {
                npk = 0.125 * v + 0.875 * npk
            }
            threshold = npk + 0.25 * (spk - npk)
        }

        // The MWI peak lags the R wave; refine to the largest deflection in the preceding window.
        val refine = (0.150 * fs).roundToInt()
        return peaks.map { p ->
            val from = (p - refine - window / 2).coerceAtLeast(0)
            val to = p.coerceAtMost(signal.size - 1)
            (from..to).maxByOrNull { abs(signal[it]) } ?: p
        }.distinct().toIntArray()
    }

    private fun addPeak(peaks: MutableList<Int>, rr: ArrayDeque<Int>, index: Int) {
        if (peaks.isNotEmpty()) {
            rr.addLast(index - peaks.last())
            if (rr.size > 8) rr.removeFirst()
        }
        peaks.add(index)
        peaks.sort()
    }

    private fun localMaxima(x: FloatArray, halfWidth: Int): List<Int> {
        val out = mutableListOf<Int>()
        var i = halfWidth
        while (i < x.size - halfWidth) {
            var isMax = x[i] > 0f
            for (j in i - halfWidth..i + halfWidth) {
                if (x[j] > x[i]) {
                    isMax = false
                    break
                }
            }
            if (isMax) {
                out.add(i)
                i += halfWidth
            } else {
                i++
            }
        }
        return out
    }

    /** RR intervals in milliseconds. */
    fun rrIntervalsMs(peaks: IntArray, fs: Int): List<Double> = peaks.toList().zipWithNext { a, b -> (b - a) * 1000.0 / fs }

    /** Median-based heart rate, robust to a single missed or extra beat. */
    fun heartRateBpm(peaks: IntArray, fs: Int): Int? {
        val rr = rrIntervalsMs(peaks, fs).filter { it in 250.0..2500.0 }.sorted()
        if (rr.size < 3) return null
        val median = rr[rr.size / 2]
        return (60_000 / median).roundToInt()
    }
}
