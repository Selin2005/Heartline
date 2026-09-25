package com.heartline.shared.ecg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * What one second of a recording looks like. GOOD and ACCEPTABLE are usable; PAUSE is a usable
 * second without a beat (a pause is a finding, not missing contact); the rest are noise.
 */
enum class SecondKind {
    GOOD,
    ACCEPTABLE,
    PAUSE,
    MOTION,
    MUSCLE,
    FLAT
    ;

    val usable get() = this == GOOD || this == ACCEPTABLE || this == PAUSE
}

/** Signal-quality indices of one window (Li, McSharry & Clifford 2008; Clifford et al. 2012; Zhao & Zhang 2018). */
data class WindowSqi(
    /** Agreement of the two QRS detectors (bSQI). */
    val b: Double,
    /** Kurtosis of the cleaned ECG (kSQI): a clean ECG is spiky. */
    val k: Double,
    /** Power 5–15 Hz / 5–40 Hz (pSQI): the QRS band's share. */
    val p: Double,
    /** 1 − power 0–1 Hz / 0–40 Hz of the raw signal (basSQI): baseline wander. */
    val bas: Double
)

/**
 * Per-second quality from rhythm-independent signal-quality indices on 2 s windows, instead of
 * judging seconds by their amplitude (which called ectopic beats "motion" and pauses "no
 * contact"). A second is noise only when the indices say so; a second without a beat that lies
 * inside an interval between two clean beats is a PAUSE.
 */
object EcgQuality {
    /** Raw standard deviation below this (mV) means no signal at all. */
    const val FLAT_MV = 0.008

    /** Indices are computed on this window around each second (enough beats for a stable bSQI). */
    const val WINDOW_SECONDS = 4.0
    const val MAX_ABS_MV = 6.0

    const val GOOD_B = 0.8
    const val FAIR_B = 0.6
    const val GOOD_K = 5.0
    const val FAIR_K = 3.5

    /**
     * @param raw the recording (DC and drift included) and [clean] its 0.5–40 Hz version, both
     * split into [segments] (start indices; windows never cross a splice).
     */
    fun seconds(raw: FloatArray, clean: FloatArray, detection: QrsDetection, segments: List<IntRange>, fs: Int): List<SecondKind> =
        assess(raw, clean, detection, segments, fs).map { it.first }

    /** Each second's kind and, when it got that far, its indices (for tuning and diagnostics). */
    fun assess(
        raw: FloatArray,
        clean: FloatArray,
        detection: QrsDetection,
        segments: List<IntRange>,
        fs: Int
    ): List<Pair<SecondKind, WindowSqi?>> {
        val count = clean.size / fs
        return List(count) { w ->
            val centre = w * fs + fs / 2
            val seg = segments.firstOrNull { centre in it } ?: return@List SecondKind.FLAT to null
            val half = (WINDOW_SECONDS * fs / 2).toInt()
            val from = maxOf(seg.first, centre - half)
            val to = minOf(seg.last + 1, centre + half)
            if (to - from < fs) return@List SecondKind.MOTION to null // a sliver next to a splice
            kind(raw, clean, detection, from, to, w, fs)
        }
    }

    private fun kind(
        raw: FloatArray,
        clean: FloatArray,
        detection: QrsDetection,
        from: Int,
        to: Int,
        second: Int,
        fs: Int
    ): Pair<SecondKind, WindowSqi?> {
        val own = second * fs until minOf((second + 1) * fs, clean.size)
        if (own.maxOf { abs(clean[it]) } > MAX_ABS_MV) return SecondKind.MOTION to null
        val rawMean = (from until to).sumOf { raw[it].toDouble() } / (to - from)
        val rawSd = sqrt((from until to).sumOf { (raw[it] - rawMean).let { d -> d * d } } / (to - from))
        if (rawSd < FLAT_MV) return SecondKind.FLAT to null
        val sqi = sqi(raw, clean, detection, from, to, fs)
        return classify(sqi) to sqi
    }

    /**
     * Per-second verdict from the two indices that separate noise from ECG on CinC 2017 (bSQI and
     * kSQI; pSQI did not). Lenient on purpose: whether the recording as a whole is too noisy is
     * decided by [RecordQuality].
     */
    fun classify(sqi: WindowSqi): SecondKind = when {
        sqi.b >= GOOD_B && sqi.k >= GOOD_K -> SecondKind.GOOD
        (sqi.b >= GOOD_B && sqi.k >= FAIR_K) || (sqi.b >= FAIR_B && sqi.k >= GOOD_K) -> SecondKind.ACCEPTABLE
        sqi.bas < 0.5 -> SecondKind.MOTION // most power below 1 Hz: movement, baseline
        else -> SecondKind.MUSCLE
    }

    fun sqi(raw: FloatArray, clean: FloatArray, detection: QrsDetection, from: Int, to: Int, fs: Int): WindowSqi {
        val x = clean.copyOfRange(from, to)
        val r = raw.copyOfRange(from, to).let { v ->
            val m = v.average().toFloat()
            FloatArray(v.size) { v[it] - m }
        }
        val p = bandPower(x, fs, 5.0, 15.0) / bandPower(x, fs, 5.0, 40.0).coerceAtLeast(1e-12)
        val bas = 1 - bandPower(r, fs, 0.0, 1.0) / bandPower(r, fs, 0.0, 40.0).coerceAtLeast(1e-12)
        return WindowSqi(QrsDetector.agreement(detection, from, to, fs), EcgContactCheck.kurtosis(x), p, bas)
    }

    /** Power between [lo] and [hi] Hz at 0.5 Hz steps (Goertzel: one multiply per sample and frequency). */
    internal fun bandPower(x: FloatArray, fs: Int, lo: Double, hi: Double): Double {
        var total = 0.0
        var f = maxOf(lo, 0.5)
        while (f <= hi) {
            val coeff = 2 * cos(2 * PI * f / fs)
            var s1 = 0.0
            var s2 = 0.0
            for (v in x) {
                val s0 = v + coeff * s1 - s2
                s2 = s1
                s1 = s0
            }
            total += s1 * s1 + s2 * s2 - coeff * s1 * s2
            f += 0.5
        }
        return total
    }

    /**
     * Seconds without a beat of their own that lie inside an interval between two clean beats of
     * the same segment are pauses, as long as they aren't flat (no contact) or wild (motion).
     */
    fun markPauses(kinds: List<SecondKind>, peaks: IntArray, valid: BooleanArray, segments: List<IntRange>, fs: Int): List<SecondKind> =
        kinds.mapIndexed { w, kind ->
            if (kind == SecondKind.FLAT || kind == SecondKind.GOOD || kind == SecondKind.ACCEPTABLE) return@mapIndexed kind
            val centre = w * fs + fs / 2
            val before = peaks.indices.lastOrNull { peaks[it] <= centre }
            val after = peaks.indices.firstOrNull { peaks[it] > centre }
            if (before == null || after == null || !valid[before] || !valid[after]) return@mapIndexed kind
            val sameSegment = segments.any { peaks[before] in it && peaks[after] in it }
            val beatsInside = peaks.any { it in w * fs until (w + 1) * fs }
            if (sameSegment && !beatsInside && peaks[after] - peaks[before] > 1.2 * fs) SecondKind.PAUSE else kind
        }

    internal fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/**
 * Is the recording as a whole too noisy to read? A logistic model on the distribution of its
 * window indices, fitted on PhysioNet/CinC 2017 (8528 single-lead recordings; noisy vs the rest,
 * 5-fold cross-validated AUC 0.94; tools/ecg-eval). At [NOISY] it flags ≈ 75 % of the recordings
 * experts called too noisy, and ≈ 5 % of normal, 4 % of AF and 6.5 % of other-rhythm recordings.
 */
object RecordQuality {
    const val NOISY = 0.75

    /** An AFib call on a recording this likely to be noise is downgraded to inconclusive. */
    const val AF_GUARD = 0.4

    private val weights = doubleArrayOf(-0.831, -4.0462, 0.4927, -2.3731, -0.3999, -0.0251)
    private const val BIAS = 6.4708

    /** Features: mean bSQI, share of windows with bSQI ≥ 0.8, median and 25th percentile of ln kSQI, median basSQI, coverage. */
    fun noiseProbability(sqis: List<WindowSqi?>): Double {
        val v = sqis.filterNotNull()
        if (v.isEmpty()) return 1.0
        val lnK = v.map { kotlin.math.ln(it.k.coerceAtLeast(1e-3)) }.sorted()
        val features = doubleArrayOf(
            v.map { it.b }.average(),
            v.count { it.b >= 0.8 }.toDouble() / v.size,
            EcgQuality.median(lnK),
            percentile(lnK, 0.25),
            EcgQuality.median(v.map { it.bas }),
            v.size.toDouble() / sqis.size
        )
        val z = BIAS + features.indices.sumOf { weights[it] * features[it] }
        return 1 / (1 + kotlin.math.exp(-z))
    }

    /** Linear-interpolated percentile of sorted values (numpy's default). */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val i = pos.toInt()
        return if (i + 1 < sorted.size) sorted[i] + (sorted[i + 1] - sorted[i]) * (pos - i) else sorted[i]
    }
}

/**
 * Beats grouped by shape. One template (as before) called every beat of another shape noise; here
 * beats that resemble each other form their own cluster, so ventricular or aberrant beats are a
 * second morphology, not noise. Noise is measured over the QRS only (−60…+80 ms), against the
 * beat's own cluster, so AF's f waves and a preceding T wave don't count as noise.
 */
object BeatClusters {
    const val JOIN_CORRELATION = 0.85
    const val MAX_NOISE_RATIO = 0.25
    const val MIN_DISTINCT_BEATS = 3
    const val DISTINCT_CORRELATION = 0.7
    const val DISTINCT_WIDTH_MS = 30.0

    data class Beats(
        /** Cluster of each beat; 0 is the dominant (most common) shape. */
        val cluster: IntArray,
        val clusterSizes: IntArray,
        /** Correlation of each beat with its cluster's template over the QRS. */
        val correlation: DoubleArray,
        /** QRS residual RMS / the cluster template's QRS range. */
        val noiseRatio: DoubleArray,
        /** QRS width of each cluster's template, ms. */
        val qrsWidthMs: DoubleArray,
        /** Peak-to-peak of the dominant template, mV. */
        val templateRangeMv: Double,
        val inverted: Boolean,
        /** Clusters whose shape is clearly unlike the dominant one (not just noise-split from it). */
        val distinct: BooleanArray = BooleanArray(clusterSizes.size)
    ) {
        fun isSingleton(beat: Int) = clusterSizes[cluster[beat]] < 2

        /** The beat belongs to a clearly different morphology (ventricular-like, aberrant). */
        fun isOtherShape(beat: Int) = cluster[beat] != 0 && distinct[cluster[beat]]
    }

    fun assess(clean: FloatArray, peaks: IntArray, fs: Int): Beats {
        val pre = (0.10 * fs).toInt()
        val post = (0.15 * fs).toInt()
        val len = pre + post
        val inside = peaks.indices.filter { peaks[it] - pre >= 0 && peaks[it] + post < clean.size }
        fun window(b: Int) = DoubleArray(len) { k -> clean[peaks[b] - pre + k].toDouble() }
        if (inside.size < 3) {
            return Beats(
                IntArray(peaks.size),
                intArrayOf(peaks.size),
                DoubleArray(peaks.size),
                DoubleArray(peaks.size) {
                    1.0
                },
                doubleArrayOf(0.0),
                0.0,
                false
            )
        }
        val windows = peaks.indices.associateWith { if (it in inside) window(it) else null }
        fun median(members: List<Int>) = DoubleArray(len) { k -> EcgQuality.median(members.mapNotNull { windows[it]?.get(k) }) }

        // Seed with the recording's median beat, then greedily grow clusters of mutually similar beats.
        val global = median(inside)
        val cluster = IntArray(peaks.size) { -1 }
        val templates = mutableListOf(global)
        for (b in inside) if (correlation(global, windows[b]!!) >= JOIN_CORRELATION) cluster[b] = 0
        for (b in inside) {
            if (cluster[b] >= 0) continue
            val w = windows[b]!!
            val best = templates.indices.drop(1).maxByOrNull { correlation(templates[it], w) }
            if (best != null && correlation(templates[best], w) >= JOIN_CORRELATION) {
                cluster[b] = best
            } else {
                templates += w
                cluster[b] = templates.lastIndex
            }
        }
        // Refine templates as the median of their members, then reassign once.
        for (c in templates.indices) inside.filter { cluster[it] == c }.takeIf { it.isNotEmpty() }?.let { templates[c] = median(it) }
        for (b in inside) cluster[b] = templates.indices.maxBy { correlation(templates[it], windows[b]!!) }
        // Renumber by size, but the dominant (0) is the narrowest QRS among the large clusters: in
        // bigeminy half the beats are ventricular, and those are the wide ones.
        val count = { c: Int -> inside.count { cluster[it] == c } }
        val largest = templates.indices.maxOf(count)
        val dominantCluster = templates.indices.filter { count(it) >= 0.3 * largest }.minBy { qrsWidth(templates[it], pre, fs) }
        val order = listOf(dominantCluster) + templates.indices.filter { it != dominantCluster }.sortedByDescending(count)
        val remap = IntArray(templates.size).also { m -> order.forEachIndexed { i, c -> m[c] = i } }
        val sortedTemplates = order.map { templates[it] }
        for (b in peaks.indices) cluster[b] = if (cluster[b] < 0) 0 else remap[cluster[b]]
        val sizes = IntArray(sortedTemplates.size) { c -> inside.count { cluster[it] == c } }

        val qrsFrom = pre - (0.06 * fs).toInt()
        val qrsTo = pre + (0.08 * fs).toInt()
        val corr = DoubleArray(peaks.size)
        val noise = DoubleArray(peaks.size) { 1.0 }
        for (b in inside) {
            val t = sortedTemplates[cluster[b]]
            val w = windows[b]!!
            corr[b] = correlation(t, w)
            val range = (qrsFrom until qrsTo).maxOf { t[it] } - (qrsFrom until qrsTo).minOf { t[it] }
            if (range <= 0) continue
            val offset = (qrsFrom until qrsTo).sumOf { w[it] - t[it] } / (qrsTo - qrsFrom)
            noise[b] = sqrt((qrsFrom until qrsTo).sumOf { (w[it] - t[it] - offset).let { d -> d * d } } / (qrsTo - qrsFrom)) / range
        }
        val dominant = sortedTemplates[0]
        val baseline = EcgQuality.median(dominant.toList())
        val inverted = baseline - dominant[pre] > dominant[pre] - baseline
        val widths = DoubleArray(sortedTemplates.size) { qrsWidth(sortedTemplates[it], pre, fs) }
        val dominantRange = dominant.max() - dominant.min()
        // A real second morphology: several beats, and a clearly different shape, width or size.
        val distinct = BooleanArray(sortedTemplates.size) { c ->
            val t = sortedTemplates[c]
            val range = t.max() - t.min()
            c != 0 &&
                sizes[c] >= MIN_DISTINCT_BEATS &&
                (
                    correlation(t, dominant) < DISTINCT_CORRELATION ||
                        abs(widths[c] - widths[0]) > DISTINCT_WIDTH_MS ||
                        range / dominantRange.coerceAtLeast(1e-9) !in 0.6..1.6
                    )
        }
        return Beats(cluster, sizes, corr, noise, widths, dominantRange, inverted, distinct)
    }

    /** Span around R where the template stays above 20 % of its R deflection, ms. */
    internal fun qrsWidth(t: DoubleArray, pre: Int, fs: Int): Double {
        val base = EcgQuality.median(t.toList())
        val r = abs(t[pre] - base).coerceAtLeast(1e-9)
        var a = pre
        while (a > 0 && abs(t[a - 1] - base) > 0.2 * r) a--
        var b = pre
        while (b < t.size - 1 && abs(t[b + 1] - base) > 0.2 * r) b++
        return (b - a + 1) * 1000.0 / fs
    }

    internal fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val am = a.average()
        val bm = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (k in a.indices) {
            val x = a[k] - am
            val y = b[k] - bm
            num += x * y
            da += x * x
            db += y * y
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }
}
