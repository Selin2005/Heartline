// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cleans a raw single-lead ECG for display and analysis. */
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

        // Five-point derivative, squared (and its magnitude, for telling T waves from QRS).
        val sq = FloatArray(band.size)
        val slope = FloatArray(band.size)
        for (i in 2 until band.size - 2) {
            val d = (-band[i - 2] - 2 * band[i - 1] + 2 * band[i + 1] + band[i + 2]) / 8f
            sq[i] = d * d
            slope[i] = abs(d)
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
        val tWindow = (0.360 * fs).roundToInt()
        fun maxSlope(c: Int) = (maxOf(0, c - window)..c).maxOf { slope[it] }
        var lastSlope = 0f
        val relearnAfter = (2.5 * fs).roundToInt()
        var lastEvent = 0
        for (c in candidates) {
            val v = mwi[c]
            if (peaks.isNotEmpty() && c - peaks.last() < refractory) continue
            // No beat for 2.5 s (typically after an artefact lifted the thresholds): re-learn them
            // from the last 2 s, so detection doesn't stay blind for the rest of the recording.
            if (c - maxOf(lastEvent, peaks.lastOrNull() ?: 0) > relearnAfter) {
                val from = maxOf(0, c - 2 * fs)
                val seconds = listOf(from until from + fs, from + fs until c).filter { !it.isEmpty() }
                spk = seconds.map { w -> w.maxOf { mwi[it] }.toDouble() }.sorted().let { it[it.size / 2] } * 0.5
                npk = (from until c).map { mwi[it].toDouble() }.average() * 0.5
                threshold = npk + 0.25 * (spk - npk)
                lastEvent = c
            }
            // Pan & Tompkins' T-wave discrimination: within 360 ms of a QRS, a candidate whose steepest
            // slope is under half of that QRS's is its T wave (tall T waves were counted as beats).
            if (peaks.isNotEmpty() && c - peaks.last() < tWindow && v > threshold && maxSlope(c) < 0.5f * lastSlope) {
                npk = 0.125 * v + 0.875 * npk
                threshold = npk + 0.25 * (spk - npk)
                continue
            }
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
                lastSlope = maxSlope(c)
                // An artefact far above the running signal level shouldn't lift the threshold with it.
                spk = 0.125 * minOf(v.toDouble(), 3 * spk) + 0.875 * spk
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

/**
 * Second QRS detector, gradient based (as in NeuroKit2's default, Makowski et al. 2021): the
 * smoothed absolute slope over 100 ms against its 750 ms average marks QRS regions, and the R peak
 * is the largest deflection in each. It keeps wide QRS complexes (ventricular beats, bundle-branch
 * block) that a 5–15 Hz band-pass weakens.
 */
object GradientQrsDetector {
    fun detect(clean: FloatArray, fs: Int): IntArray {
        if (clean.size < fs) return IntArray(0)
        val grad = FloatArray(clean.size) { i -> if (i in 1 until clean.size - 1) abs(clean[i + 1] - clean[i - 1]) / 2f else 0f }
        val qrs = boxcar(grad, (0.1 * fs).roundToInt())
        val avg = boxcar(grad, (0.75 * fs).roundToInt())
        val minLen = (0.4 * 0.1 * fs).roundToInt()
        // 360 ms, as Pan-Tompkins' T-wave window: of two regions this close, the steeper is the QRS.
        val minDelay = (0.36 * fs).roundToInt()
        val peaks = mutableListOf<Int>()
        val steepness = mutableListOf<Float>()
        var i = 0
        while (i < clean.size) {
            if (qrs[i] <= 1.5f * avg[i]) {
                i++
                continue
            }
            val start = i
            while (i < clean.size && qrs[i] > 1.5f * avg[i]) i++
            if (i - start < minLen) continue
            val peak = (start until i).maxBy { abs(clean[it]) }
            val steep = (start until i).maxOf { grad[it] }
            if (peaks.isNotEmpty() && peak - peaks.last() < minDelay) {
                if (steep > steepness.last()) {
                    peaks[peaks.lastIndex] = peak
                    steepness[steepness.lastIndex] = steep
                }
            } else {
                peaks += peak
                steepness += steep
            }
        }
        return peaks.toIntArray()
    }

    private fun boxcar(x: FloatArray, w: Int): FloatArray {
        val out = FloatArray(x.size)
        var acc = 0.0
        val half = w / 2
        for (i in 0 until x.size + half) {
            if (i < x.size) acc += x[i]
            if (i - w >= 0) acc -= x[i - w]
            val c = i - half
            if (c in x.indices) out[c] = (acc / w).toFloat()
        }
        return out
    }
}

/** Both detectors' beats and their union ([peaks]); their agreement is a signal-quality index (bSQI). */
class QrsDetection(val peaks: IntArray, val primary: IntArray, val secondary: IntArray)

object QrsDetector {
    /** Two detections of the same beat, ms. */
    const val MATCH_MS = 100

    /**
     * Beats are Pan-Tompkins' (with T-wave discrimination). The gradient detector runs alongside
     * only for the agreement index: merging its extra beats added false beats in noise
     * (NSTDB 12 dB: PPV 0.885 → 0.841) without finding more real ones on MIT-BIH.
     */
    fun detect(clean: FloatArray, fs: Int): QrsDetection {
        val primary = RPeakDetector.detect(clean, fs)
        val secondary = GradientQrsDetector.detect(clean, fs)
        return QrsDetection(primary, primary, secondary)
    }

    /** Share of beats both detectors agree on in [from, to): matched / (n1 + n2 − matched); 1 when neither finds any. */
    fun agreement(d: QrsDetection, from: Int, to: Int, fs: Int): Double {
        val a = d.primary.filter { it in from until to }
        val b = d.secondary.filter { it in from until to }
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val tol = MATCH_MS * fs / 1000
        val matched = a.count { x -> b.any { abs(it - x) <= tol } }
        return matched.toDouble() / (a.size + b.size - matched)
    }
}
