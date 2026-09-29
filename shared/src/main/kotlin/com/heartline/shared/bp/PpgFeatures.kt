// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import com.heartline.shared.dsp.Biquad
import com.heartline.shared.dsp.filtFilt
import com.heartline.shared.hr.RrFeatures
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * Pulse-wave morphology of one recording, measured on the ensemble-averaged beat (see
 * docs/algorithms/BP_ALGORITHM.md). Fields added in algorithm 2 default to 0 so older data still decodes.
 */
@Serializable
data class PpgFeatureVector(
    val heartRateBpm: Double,
    /** Foot-to-peak time as a fraction of the beat. */
    val riseFraction: Double,
    /** Pulse width at half amplitude, fraction of the beat. */
    val widthFraction: Double,
    /** Area after the systolic peak / area before it (wave reflection). */
    val areaRatio: Double,
    /** Beats used; also a quality signal. */
    val beats: Int,
    /** 0..1: how closely the beats match their average. */
    val quality: Double,
    /** Systolic upstroke time (foot → peak), ms. */
    val upstrokeMs: Double = 0.0,
    /** Pulse width at 50 % amplitude, ms. */
    val width50Ms: Double = 0.0,
    /** Pulse width at 25 % amplitude, ms (diastolic runoff). */
    val width25Ms: Double = 0.0,
    /** Second-derivative (acceleration plethysmogram) wave ratios b/a and d/a (Takazawa 1998). */
    val apgBa: Double = 0.0,
    val apgDa: Double = 0.0,
    /** The raw signal was upside down (Galaxy Watch green PPG usually is) and was flipped. */
    val inverted: Boolean = false,
    val version: Int = 1,
    /**
     * Algorithm 3: systolic peak → diastolic peak (or inflection) time, ms. The reflected wave
     * returns sooner as arteries stiffen and pressure rises (stiffness index, Millasseau 2002).
     */
    val reflectionDelayMs: Double = 0.0,
    /** Height of the diastolic peak/inflection relative to the systolic peak (reflection index). */
    val reflectionIndex: Double = 0.0,
    /** Beat-to-beat variability of the kept beats (RMSSD, ms). */
    val rmssdMs: Double = 0.0,
    /** Skewness of the filtered signal: clean PPG is clearly skewed (Elgendi 2016 SQI). */
    val skewness: Double = 0.0,
    /** The ensemble beat resampled to [SHAPE_POINTS] points (0..1), for the phone's learned model. */
    val shape: List<Float> = emptyList(),
    // Algorithm 5 (v4): rhythm and haemodynamic state, used to decide whether the recording is in
    // a steady state at all (see HemodynamicStateClassifier). All default to 0 = unknown.
    /** Coefficient of variation of the beat-to-beat intervals, premature beats excluded. */
    val ibiCv: Double = 0.0,
    /** Premature (ectopic) beats found: a short interval followed by a compensatory pause. */
    val ectopicCount: Int = 0,
    /** Share of plausible beats left out of the ensemble (ectopic, post-ectopic or off-length). */
    val rejectedFraction: Double = 0.0,
    /** Linear trend of the instantaneous pulse rate over the recording, bpm per second. */
    val hrSlopeBpmPerS: Double = 0.0,
    /** Perfusion index, % (pulse amplitude / mean light level); 0 when the raw level is unknown. */
    val perfusionIndex: Double = 0.0,
    /** Relative change of the pulse amplitude from the first to the last third of the recording. */
    val amplitudeTrend: Double = 0.0,
    // v5: the irregular-rhythm features the app's background notification uses (RrFeatures,
    // Dash 2009) on the clean intervals: normalised RMSSD, Shannon entropy, turning point ratio.
    val rrNRmssd: Double = 0.0,
    val rrEntropy: Double = 0.0,
    val rrTurningPoint: Double = 0.0,
    val rrCount: Int = 0
) {
    fun asArray() = doubleArrayOf(heartRateBpm, riseFraction, widthFraction, areaRatio)

    /** Features used by [BpEstimator]; see [BpEstimator.FEATURE_MIN_VERSION] for when each became available. */
    fun modelArray() = doubleArrayOf(heartRateBpm, upstrokeMs, width50Ms, areaRatio, apgBa, apgDa, reflectionDelayMs)

    /** Beat length, ms. */
    val beatMs: Double get() = 60_000.0 / heartRateBpm.coerceAtLeast(1.0)

    companion object {
        /** Current extractor. */
        const val VERSION = 5

        /** First extractor with the rhythm and state fields (algorithm 5). */
        const val STATE_VERSION = 4

        /** Oldest extractor whose features the estimator still accepts (the 6 algorithm-2 features are unchanged). */
        const val MIN_MODEL_VERSION = 2

        const val SHAPE_POINTS = 32
    }
}

/**
 * Extracts [PpgFeatureVector] from green PPG (PPG_ON_DEMAND, 100 Hz):
 * band-pass 0.5–8 Hz → polarity check (arterial pulses rise fast and fall slowly) → systolic
 * peaks and feet → beats of plausible length → ensemble average beat (aligned on the foot) →
 * morphology on that average, which is far less noisy than per-beat values.
 */
object PpgFeatures {
    /**
     * [polarity]: read the signal this way up (true = upside down) instead of detecting it. A
     * calibration fixes it once, so a measurement is never compared with a wave read the other
     * way up (algorithm 6.2).
     */
    fun extract(raw: FloatArray, fs: Int, polarity: Boolean? = null): PpgFeatureVector? {
        val detected = detect(raw, fs, polarity) ?: return null
        val x = detected.x
        val beats = detected.beats
        val rhythm = rhythm(detected, fs)
        val lengths = beats.map { (a, b) -> b - a }.sorted()
        val len = lengths[lengths.size / 2]
        // Only beats within 20 % of the typical length: an ectopic or a missed foot would smear the
        // average. The premature beat, its compensatory pause and the stronger beat after it
        // (post-extrasystolic potentiation) are left out too.
        // In an irregular rhythm (AF) a pulse's shape also depends on the interval before it (filling
        // time, the previous beat's tail): only beats whose previous interval is typical too are kept.
        val irregular = HemodynamicStateClassifier.irregular(rhythm)
        fun typical(i: Int) = abs((beats[i].second - beats[i].first) - len) <= len * 0.2
        val kept = beats.filterIndexed { i, _ ->
            i !in rhythm.excluded &&
                typical(i) &&
                (!irregular || (i > 0 && beats[i - 1].second == beats[i].first && typical(i - 1)))
        }
        if (kept.size < 5) return null

        // Each beat normalised to 0..1 (foot..peak) and stretched to the typical length, at 4× the
        // sample rate so timings aren't quantised to 10 ms steps.
        val up = UPSAMPLE
        val n = len * up
        val shapes = kept.map { (a, b) -> normalise(resample(x, a, b, n)) }
        val template = DoubleArray(n) { k -> shapes.map { it[k] }.sorted()[shapes.size / 2] }
        if (template.max() < 0.5) return null
        val correlations = shapes.map { correlation(it, template) }
        val quality = correlations.sorted()[correlations.size / 2].coerceIn(0.0, 1.0) *
            (correlations.count { it > 0.9 }.toDouble() / correlations.size)

        val peakIdx = template.indices.maxBy { template[it] }
        val (reflectionIdx, reflectionHeight) = reflection(template, peakIdx, fs * up)
        val keptIntervals = kept.map { (a, b) -> (b - a) * 1000.0 / fs }
        val rmssd = if (keptIntervals.size < 3) 0.0 else sqrt(keptIntervals.zipWithNext { a, b -> (b - a) * (b - a) }.average())
        val areaBefore = (0..peakIdx).sumOf { template[it] }
        val areaAfter = (peakIdx until n).sumOf { template[it] }
        val (ba, da) = apgRatios(template, fs * up)
        val msPerSample = 1000.0 / (fs * up)
        return PpgFeatureVector(
            heartRateBpm = 60.0 * fs / len,
            riseFraction = peakIdx.toDouble() / n,
            widthFraction = template.count { it >= 0.5 }.toDouble() / n,
            areaRatio = areaAfter / areaBefore.coerceAtLeast(1e-6),
            beats = kept.size,
            quality = quality,
            upstrokeMs = peakIdx * msPerSample,
            width50Ms = template.count { it >= 0.5 } * msPerSample,
            width25Ms = template.count { it >= 0.25 } * msPerSample,
            apgBa = ba,
            apgDa = da,
            inverted = detected.inverted,
            version = PpgFeatureVector.VERSION,
            reflectionDelayMs = reflectionIdx?.let { (it - peakIdx) * msPerSample } ?: 0.0,
            reflectionIndex = reflectionHeight,
            rmssdMs = rmssd,
            skewness = skewness(x),
            shape = List(PpgFeatureVector.SHAPE_POINTS) { k -> template[k * (n - 1) / (PpgFeatureVector.SHAPE_POINTS - 1)].toFloat() },
            ibiCv = rhythm.ibiCv,
            ectopicCount = rhythm.ectopicCount,
            rejectedFraction = 1.0 - kept.size.toDouble() / beats.size,
            hrSlopeBpmPerS = rhythm.hrSlopeBpmPerS,
            perfusionIndex = perfusionIndex(raw, x, kept),
            amplitudeTrend = amplitudeTrend(x, kept),
            rrNRmssd = rhythm.rr?.nRmssd ?: 0.0,
            rrEntropy = rhythm.rr?.shannonEntropy ?: 0.0,
            rrTurningPoint = rhythm.rr?.turningPointRatio ?: 0.0,
            rrCount = rhythm.rr?.count ?: 0
        )
    }

    /** Filtered, upright signal and its plausible beats (foot to next foot, sample indices). */
    private class Detected(val x: FloatArray, val inverted: Boolean, val beats: List<Pair<Int, Int>>)

    private fun detect(raw: FloatArray, fs: Int, polarity: Boolean? = null): Detected? {
        if (raw.size < fs * 8) return null
        val filtered = raw.filtFilt(Biquad.highPass(0.5, fs.toDouble()), Biquad.lowPass(8.0, fs.toDouble()))
        if ((filtered.max() - filtered.min()) < 1e-6f) return null
        // Raw light intensity (a large offset, as the watch reports it) falls as blood volume
        // rises: upside down unless the slopes very clearly say otherwise (a fast pulse can make
        // the two slopes similar; a wrong flip ruined a real calibration round at 106 bpm).
        val lightIntensity = abs(raw.average()) > LIGHT_DC_FACTOR * (filtered.max() - filtered.min())
        val inverted = polarity ?: if (lightIntensity) !isUpright(filtered) else isInverted(filtered)
        val x = if (inverted) FloatArray(filtered.size) { -filtered[it] } else filtered

        val half = (0.25 * fs).toInt()
        val maxima = (half until x.size - half).filter { i -> (i - half..i + half).all { x[it] <= x[i] } }
        if (maxima.size < 6) return null
        // Low enough to keep a weak premature beat, so it can be recognised and left out.
        val threshold = maxima.map { x[it] }.sorted()[maxima.size / 4] * 0.35f
        val peaks = mutableListOf<Int>()
        for (m in maxima.filter { x[it] >= threshold }) {
            if (peaks.isNotEmpty() && m - peaks.last() < fs * 0.35) {
                if (x[m] > x[peaks.last()]) peaks[peaks.lastIndex] = m
            } else {
                peaks += m
            }
        }
        val lookBack = (0.35 * fs).toInt()
        val feet = peaks.filter { it - lookBack >= 0 }.map { p -> (p - lookBack..p).minBy { x[it] } }.distinct()
        if (feet.size < 6) return null
        val beats = feet.zipWithNext().filter { (a, b) -> b - a in (fs * 0.33).toInt()..(fs * 1.6).toInt() }
        if (beats.size < 5) return null
        return Detected(x, inverted, beats)
    }

    /**
     * One pulse: foot and next foot (sample indices), the steepest point of its upstroke, the
     * intersecting-tangent onset (fractional index: where the tangent at the steepest point meets
     * the foot level; the standard transit-time marker, which unlike the upstroke doesn't move
     * with the ejection time) and its height above the foot (filtered, upright units).
     */
    data class Pulse(val foot: Int, val nextFoot: Int, val upstroke: Int, val onset: Double, val amplitude: Double)

    /** Every plausible pulse of a recording, in order (for beat-to-beat analysis such as the arm-raise maneuver). */
    fun pulses(raw: FloatArray, fs: Int, polarity: Boolean? = null): List<Pulse>? {
        val d = detect(raw, fs, polarity) ?: return null
        return d.beats.map { (a, b) ->
            val peak = (a..b).maxBy { d.x[it] }
            val up = if (peak > a + 1) (a + 1 until peak).maxBy { d.x[it + 1] - d.x[it - 1] } else a
            Pulse(a, b, up, tangentOnset(d.x, a, up), (d.x[peak] - d.x[a]).toDouble())
        }
    }

    /** Intersecting tangent: the steepest-slope line at [up] meets the level of the foot [foot]. */
    internal fun tangentOnset(x: FloatArray, foot: Int, up: Int): Double {
        if (up <= 0 || up >= x.size - 1) return up.toDouble()
        val slope = (x[up + 1] - x[up - 1]) / 2.0
        if (slope <= 1e-12) return up.toDouble()
        return (up - (x[up] - x[foot]) / slope).coerceIn(foot.toDouble(), up.toDouble())
    }

    /**
     * Beat-to-beat rhythm of a recording, also when it is too irregular for a feature vector
     * (atrial fibrillation smears every beat length), so the app can say "irregular rhythm"
     * instead of "poor signal". Null when no pulse is found at all.
     */
    fun rhythm(raw: FloatArray, fs: Int, polarity: Boolean? = null): PpgRhythm? = detect(raw, fs, polarity)?.let { rhythm(it, fs) }

    private fun rhythm(detected: Detected, fs: Int): PpgRhythm {
        val beats = detected.beats
        val lengths = beats.map { (a, b) -> (b - a).toDouble() }
        val median = lengths.sorted()[lengths.size / 2]
        fun consecutive(i: Int) = i + 1 < beats.size && beats[i].second == beats[i + 1].first
        // A premature beat shortens one interval and is followed by a compensatory pause.
        val excluded = mutableSetOf<Int>()
        var ectopic = 0
        var i = 0
        while (i < beats.size - 1) {
            // Against the local rhythm (the 2 intervals on each side), not the whole recording's:
            // breathing slowly speeds and slows the pulse (sinus arrhythmia), which is not ectopy.
            val local = (maxOf(0, i - 2)..minOf(lengths.size - 1, i + 3)).filter { it != i && it != i + 1 }.map { lengths[it] }.sorted()
            val ref = if (local.isEmpty()) median else local[local.size / 2]
            if (lengths[i] < ref * ECTOPIC_SHORT && consecutive(i) && lengths[i + 1] > ref * ECTOPIC_LONG) {
                ectopic++
                excluded += i
                excluded += i + 1
                if (consecutive(i + 1)) excluded += i + 2
                i += 2
            } else {
                i++
            }
        }
        val clean = beats.indices.filter { it !in excluded }
        val intervals = clean.map { lengths[it] }
        val mean = intervals.average()
        val cv = if (intervals.size < 3 || mean <= 0) {
            0.0
        } else {
            sqrt(intervals.sumOf { (it - mean) * (it - mean) } / (intervals.size - 1)) / mean
        }
        // Least-squares slope of the instantaneous rate against time.
        val slope = if (clean.size < 5) {
            0.0
        } else {
            val t = clean.map { beats[it].first.toDouble() / fs }
            val hr = clean.map { 60.0 * fs / lengths[it] }
            val tm = t.average()
            val hm = hr.average()
            val varT = t.sumOf { (it - tm) * (it - tm) }
            if (varT <= 1e-9) 0.0 else t.indices.sumOf { (t[it] - tm) * (hr[it] - hm) } / varT
        }
        // Do the pulses look alike, whatever their spacing? (Irregular rhythm: yes; noise: no.)
        val len = median.toInt()
        val shapes = clean.map { normalise(resample(detected.x, beats[it].first, beats[it].second, len)) }
        val template = DoubleArray(len) { k -> shapes.map { it[k] }.sorted()[shapes.size / 2] }
        val shapeQuality = shapes.map { correlation(it, template) }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        // The app's own irregular-rhythm features (the background notification's, Dash 2009) on the
        // clean beat-to-beat intervals.
        val rr = RrFeatures.of(intervals.map { it * 1000.0 / fs })
        return PpgRhythm(
            beats = beats.size,
            heartRateBpm = 60.0 * fs / median,
            ibiCv = cv,
            ectopicCount = ectopic,
            hrSlopeBpmPerS = slope,
            shapeQuality = shapeQuality,
            excluded = excluded,
            rr = rr
        )
    }

    /** Pulse amplitude of each beat (peak above its foot) on the filtered, upright signal. */
    private fun amplitudes(x: FloatArray, beats: List<Pair<Int, Int>>) = beats.map { (a, b) -> ((a..b).maxOf { x[it] } - x[a]).toDouble() }

    /**
     * AC/DC × 100 with DC the mean raw light level. Only meaningful for raw intensity (a large
     * positive or negative offset, as the watch reports); a signal without one gives 0 (unknown).
     */
    private fun perfusionIndex(raw: FloatArray, x: FloatArray, beats: List<Pair<Int, Int>>): Double {
        val dc = abs(raw.average())
        val ac = amplitudes(x, beats).sorted().let { it[it.size / 2] }
        if (dc <= 0.0 || ac <= 0.0 || ac / dc > MAX_AC_DC) return 0.0
        return 100.0 * ac / dc
    }

    /** Mean amplitude of the last third of the beats relative to the first third, minus 1. */
    private fun amplitudeTrend(x: FloatArray, beats: List<Pair<Int, Int>>): Double {
        val amp = amplitudes(x, beats)
        val third = amp.size / 3
        if (third < 2) return 0.0
        val first = amp.take(third).average()
        val last = amp.takeLast(third).average()
        return if (first <= 1e-9) 0.0 else last / first - 1.0
    }

    /** Premature: shorter than this share of the typical interval, followed by a pause longer than [ECTOPIC_LONG]. */
    private const val ECTOPIC_SHORT = 0.8
    private const val ECTOPIC_LONG = 1.1

    /** Pulse amplitude above this share of the light level means there is no real DC offset. */
    private const val MAX_AC_DC = 0.2

    /**
     * The reflected (diastolic) wave: the first local maximum of the beat after the systolic peak,
     * or, when the two merge (stiff arteries, older users), the inflection point where the downslope
     * flattens most (maximum of the first derivative). Searched 80–500 ms after the peak and before
     * 85 % of the beat. @return its index and its height relative to the systolic peak.
     */
    private fun reflection(template: DoubleArray, peak: Int, fs: Int): Pair<Int?, Double> {
        val smooth = gaussian(template, sigma = 0.015 * fs)
        val from = peak + (0.08 * fs).toInt()
        val to = minOf(peak + (0.5 * fs).toInt(), (template.size * 0.85).toInt())
        if (to - from < 3) return null to 0.0
        val localMax = (from + 1 until to - 1).firstOrNull {
            smooth[it] > smooth[it - 1] &&
                smooth[it] >= smooth[it + 1] &&
                smooth[it] > 0.05
        }
        val idx = localMax ?: (from + 1 until to - 1).maxByOrNull { smooth[it + 1] - smooth[it - 1] } ?: return null to 0.0
        return idx to template[idx].coerceIn(0.0, 1.0)
    }

    private fun skewness(x: FloatArray): Double {
        val m = x.average()
        var m2 = 0.0
        var m3 = 0.0
        for (v in x) {
            val d = v - m
            m2 += d * d
            m3 += d * d * d
        }
        m2 /= x.size
        m3 /= x.size
        return if (m2 <= 1e-12) 0.0 else m3 / (m2 * sqrt(m2))
    }

    /**
     * Arterial pulses rise quickly and decay slowly, so the steepest slope is positive. If the
     * steepest slopes are negative instead, the signal is upside down (raw light intensity falls
     * as blood volume rises).
     */
    private const val UPSAMPLE = 4

    /** Clearly upright: the steepest rises are at least twice the steepest falls. */
    private fun isUpright(x: FloatArray): Boolean {
        val d = FloatArray(x.size - 1) { x[it + 1] - x[it] }.sorted()
        val k = (d.size * 0.02).toInt().coerceAtLeast(1)
        return d.takeLast(k).average() > -d.take(k).average() * 2.0
    }

    /** A mean level this many times the pulse's range means raw light intensity. */
    private const val LIGHT_DC_FACTOR = 20.0

    fun isInverted(x: FloatArray): Boolean {
        val d = FloatArray(x.size - 1) { x[it + 1] - x[it] }.sorted()
        val k = (d.size * 0.02).toInt().coerceAtLeast(1)
        val steepRise = d.takeLast(k).average()
        val steepFall = -d.take(k).average()
        return steepFall > steepRise * 1.15
    }

    private fun resample(x: FloatArray, from: Int, to: Int, n: Int): DoubleArray = DoubleArray(n) { k ->
        val pos = from + k.toDouble() * (to - from) / n
        val i = pos.toInt().coerceIn(from, to - 1)
        val f = pos - i
        x[i] * (1 - f) + x[(i + 1).coerceAtMost(x.size - 1)] * f
    }

    /**
     * Removes the straight line from this foot to the next (respiration and drift tilt a beat),
     * then scales so the foot is 0 and the systolic peak 1.
     */
    private fun normalise(beat: DoubleArray): DoubleArray {
        val n = beat.size
        val start = beat.first()
        val end = beat.last()
        val detrended = DoubleArray(n) { beat[it] - (start + (end - start) * it / (n - 1).coerceAtLeast(1)) }
        val peak = detrended.max()
        if (peak <= 1e-9) return DoubleArray(n)
        return DoubleArray(n) { detrended[it] / peak }
    }

    /**
     * a = first maximum of the second derivative (early systole), b = the minimum after it,
     * d = the minimum in late systole (before the dicrotic region). Ratios are scale free.
     */
    private fun apgRatios(template: DoubleArray, fs: Int): Pair<Double, Double> {
        // Differentiating twice amplifies noise: smooth first (~10 ms Gaussian) and step 10 ms.
        val smooth = gaussian(template, sigma = 0.010 * fs)
        val h = maxOf(1, (0.010 * fs).toInt())
        val d1 = DoubleArray(smooth.size) { i -> if (i in h until smooth.size - h) (smooth[i + h] - smooth[i - h]) / (2 * h) else 0.0 }
        val d2 = DoubleArray(smooth.size) { i -> if (i in h until smooth.size - h) (d1[i + h] - d1[i - h]) / (2 * h) else 0.0 }
        val peak = template.indices.maxBy { template[it] }
        val a = (0..peak).maxByOrNull { d2[it] } ?: return 0.0 to 0.0
        if (d2[a] <= 0) return 0.0 to 0.0
        val b = (a..(a + (0.15 * fs).toInt()).coerceAtMost(template.size - 1)).minBy { d2[it] }
        val lateFrom = (peak + (0.05 * fs).toInt()).coerceAtMost(template.size - 1)
        val lateTo = (peak + (0.25 * fs).toInt()).coerceAtMost(template.size - 1)
        val d = (lateFrom..lateTo).minByOrNull { d2[it] } ?: return d2[b] / d2[a] to 0.0
        return d2[b] / d2[a] to d2[d] / d2[a]
    }

    private fun gaussian(x: DoubleArray, sigma: Double): DoubleArray {
        val radius = (3 * sigma).toInt().coerceAtLeast(1)
        val kernel = DoubleArray(2 * radius + 1) { k -> kotlin.math.exp(-0.5 * ((k - radius) / sigma).let { it * it }) }
        val norm = kernel.sum()
        return DoubleArray(x.size) { i ->
            var acc = 0.0
            for (k in kernel.indices) acc += kernel[k] * x[(i + k - radius).coerceIn(0, x.size - 1)]
            acc / norm
        }
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val am = a.average()
        val bm = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - am) * (b[i] - bm)
            da += (a[i] - am) * (a[i] - am)
            db += (b[i] - bm) * (b[i] - bm)
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }
}

/**
 * Rhythm of one PPG recording (algorithm 5). [shapeQuality]: median correlation of each pulse
 * with the typical pulse (stretched to one length), high for a clean but irregular rhythm and low
 * for noise. [excluded]: indices of beats left out of the ensemble (premature beat, compensatory
 * pause, post-ectopic beat).
 */
data class PpgRhythm(
    val beats: Int,
    val heartRateBpm: Double,
    val ibiCv: Double,
    val ectopicCount: Int,
    val hrSlopeBpmPerS: Double,
    val shapeQuality: Double = 0.0,
    val excluded: Set<Int> = emptySet(),
    val rr: RrFeatures? = null
)

/** Live pulse rate from a few seconds of filtered, upright PPG (for the measuring screen). */
object PulseRate {
    fun bpm(x: FloatArray, fs: Int): Int? {
        if (x.size < fs * 3) return null
        val sorted = x.sorted()
        val level = sorted[(sorted.size * 0.6).toInt()]
        val half = (0.2 * fs).toInt()
        val peaks = mutableListOf<Int>()
        for (i in half until x.size - half) {
            if (x[i] < level) continue
            if ((i - half..i + half).any { x[it] > x[i] }) continue
            if (peaks.isEmpty() || i - peaks.last() >= (0.33 * fs).toInt()) peaks += i
        }
        if (peaks.size < 3) return null
        val intervals = peaks.zipWithNext { a, b -> b - a }.sorted()
        return (60.0 * fs / intervals[intervals.size / 2]).toInt().takeIf { it in 35..200 }
    }
}
