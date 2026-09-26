// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import com.heartline.shared.hr.RrFeatures
import com.heartline.shared.model.EcgMetrics
import com.heartline.shared.model.EcgNote
import com.heartline.shared.model.EcgPoorReason
import com.heartline.shared.model.EcgResult
import kotlin.math.roundToInt

data class EcgAnalysis(
    val peaks: IntArray,
    val averageBpm: Int?,
    val result: EcgResult,
    val evidence: RhythmClassifier.Evidence,
    val metrics: EcgMetrics,
    /** Per-second quality and indices (diagnostics, tuning). */
    val seconds: List<Pair<SecondKind, WindowSqi?>> = emptyList(),
    /** Intervals between trustworthy beats, one list per uninterrupted run (diagnostics, tuning). */
    val runs: List<List<RrInterval>> = emptyList(),
    val noiseProbability: Double = 0.0,
    /** Shape cluster of each beat (0 = dominant). */
    val beatCluster: IntArray = IntArray(0),
    /** [RhythmFeatures] of this recording. */
    val features: DoubleArray = DoubleArray(0),
    /** The signal-quality verdict before the learned model ran (what the model is trained against). */
    val signalReason: EcgPoorReason = EcgPoorReason.NONE
)

/** Timing and contact facts the recorder knows and the signal doesn't. */
data class EcgSession(
    val startedAtMs: Long = 0,
    val endedAtMs: Long = 0,
    val leadOffSec: Float = 0f,
    val leadOffRatio: Float = 0f,
    val measuredRateHz: Float? = null,
    /** Where the recorder spliced contact stretches together (start index of each segment). */
    val segmentStarts: List<Int> = emptyList()
)

/**
 * Turns a finished recording into a result plus [EcgMetrics] (algorithm 3, docs/algorithms/ECG_ALGORITHM.md):
 * each contact segment is cleaned (0.5–40 Hz, mains notch) and searched for beats on its own (two
 * QRS detectors) → beats are grouped by shape → per-second quality from rhythm-independent
 * indices → intervals only between consecutive trustworthy beats of one segment → heart rate, HRV,
 * early beats, pauses and the rhythm class. A recording is "poor" only when there isn't enough
 * clean signal; an unusual rhythm or beat shape is never a reason.
 */
object EcgAnalyzer {
    const val MIN_USABLE_SEC = 12f
    const val MIN_VALID_BEATS = 8
    const val MIN_DURATION_SEC = 20f
    const val MAX_LEAD_OFF_RATIO = 0.5f
    const val PAUSE_MS = 2000.0
    const val ALGORITHM = 3

    /** Kept for callers that only know the lead-off ratio. */
    fun analyze(raw: FloatArray, fs: Int, leadOffRatio: Float): EcgAnalysis = analyze(raw, fs, EcgSession(leadOffRatio = leadOffRatio))

    fun analyze(raw: FloatArray, fs: Int, session: EcgSession): EcgAnalysis {
        val signal = sanitize(raw)
        val segments = segments(session.segmentStarts, signal.size)
        val clean = FloatArray(signal.size)
        val primary = mutableListOf<Int>()
        val secondary = mutableListOf<Int>()
        val merged = mutableListOf<Int>()
        for (seg in segments) {
            if (seg.count() < fs) continue
            val part = EcgFilter.clean(signal.copyOfRange(seg.first, seg.last + 1), fs)
            part.copyInto(clean, seg.first)
            val d = QrsDetector.detect(part, fs)
            primary += d.primary.map { it + seg.first }
            secondary += d.secondary.map { it + seg.first }
            merged += d.peaks.map { it + seg.first }
        }
        val detection = QrsDetection(merged.toIntArray(), primary.toIntArray(), secondary.toIntArray())
        val peaks = detection.peaks
        val beats = BeatClusters.assess(clean, peaks, fs)
        val assessed = EcgQuality.assess(signal, clean, detection, segments, fs)
        var seconds = assessed.map { it.first }
        val secondOf = { index: Int -> seconds.getOrNull(index / fs) ?: SecondKind.GOOD }
        val valid = BooleanArray(peaks.size) {
            val kind = secondOf(peaks[it])
            kind.usable && beats.noiseRatio[it] <= BeatClusters.MAX_NOISE_RATIO && !(beats.isSingleton(it) && kind != SecondKind.GOOD)
        }
        seconds = EcgQuality.markPauses(seconds, peaks, valid, segments, fs)

        // Intervals only between two consecutive trustworthy beats of the same segment.
        val segmentOf = { index: Int -> segments.indexOfFirst { index in it } }
        val runs = mutableListOf<MutableList<RrInterval>>()
        var pauses = 0
        var longestPause: Double? = null
        for (i in 1 until peaks.size) {
            val ms = (peaks[i] - peaks[i - 1]) * 1000.0 / fs
            val joined = valid[i] && valid[i - 1] && segmentOf(peaks[i]) == segmentOf(peaks[i - 1])
            if (!joined || ms !in 250.0..2500.0) {
                if (joined && ms > PAUSE_MS) {
                    pauses++
                    longestPause = maxOf(longestPause ?: 0.0, ms)
                }
                runs += mutableListOf<RrInterval>()
                continue
            }
            if (ms > PAUSE_MS) {
                pauses++
                longestPause = maxOf(longestPause ?: 0.0, ms)
            }
            if (runs.isEmpty()) runs += mutableListOf<RrInterval>()
            runs.last() += RrInterval(i, ms)
        }
        val rrRuns = runs.filter { it.isNotEmpty() }
        val rr = rrRuns.flatten().map { it.ms }
        val features = RrFeatures.of(rr)
        val averageBpm = rr.takeIf { it.size >= 3 }?.sorted()?.let { (60_000 / it[it.size / 2]).roundToInt() }
        val (minBpm, maxBpm) = hrRange(rr)
        // Early beats: by timing (short–long), or a different shape arriving early (ventricular-like).
        val differentShape = rrRuns.flatMap { run -> run.filter { beats.isOtherShape(it.beat) }.map { it.beat } }.toSet()
        val premature = RhythmClassifier.prematureBeats(rrRuns, differentShape)
        val ventricularLike = premature.count { it in differentShape }

        val duration = signal.size.toFloat() / fs
        val count = { kind: SecondKind -> seconds.count { it == kind }.toFloat() }
        val usable =
            seconds.count { it.usable }.toFloat() +
                (duration - seconds.size).coerceAtLeast(0f).let { tail -> if (seconds.lastOrNull()?.usable == true) tail else 0f }
        val motion = count(SecondKind.MOTION)
        val muscle = count(SecondKind.MUSCLE)
        val flat = count(SecondKind.FLAT)
        val validBeats = valid.count { it }
        val validFraction = if (peaks.isEmpty()) 0.0 else validBeats.toDouble() / peaks.size
        val quality = (100 * (0.6 * (usable / duration.coerceAtLeast(1f)) + 0.4 * validFraction)).roundToInt().coerceIn(0, 100)

        val noise = RecordQuality.noiseProbability(assessed.map { it.second })
        val reason = when {
            duration < MIN_DURATION_SEC -> EcgPoorReason.TOO_SHORT
            session.leadOffRatio > MAX_LEAD_OFF_RATIO -> EcgPoorReason.LEAD_OFF
            seconds.isNotEmpty() && flat == seconds.size.toFloat() -> EcgPoorReason.LOW_AMPLITUDE
            noise > RecordQuality.NOISY -> if (motion > muscle) EcgPoorReason.MOTION else EcgPoorReason.MUSCLE_NOISE
            usable < MIN_USABLE_SEC -> listOf(
                EcgPoorReason.MOTION to motion,
                EcgPoorReason.MUSCLE_NOISE to muscle,
                EcgPoorReason.LOW_AMPLITUDE to flat
            ).maxBy { it.second }.first
            validBeats < MIN_VALID_BEATS || averageBpm == null || features == null -> EcgPoorReason.TOO_FEW_BEATS
            else -> EcgPoorReason.NONE
        }

        // P wave from normal-shaped beats that aren't early.
        val normalBeats = peaks.filterIndexed { i, _ -> valid[i] && beats.cluster[i] == 0 && i !in premature }.toIntArray()
        val pWave = RhythmClassifier.pWaveAmplitude(clean, normalBeats, fs)
        val pRelative = if (pWave != null && beats.templateRangeMv > 0) pWave / beats.templateRangeMv else null
        val rules = RhythmClassifier.classify(reason, averageBpm, rrRuns, pRelative, pauses, differentShape).let {
            // Irregular intervals on a doubtful recording are more likely noise than AFib.
            if (it.result == EcgResult.AFIB_SIGNS &&
                noise > RecordQuality.AF_GUARD
            ) {
                RhythmClassifier.Decision(EcgResult.INCONCLUSIVE, EcgNote.NOISY_RHYTHM)
            } else {
                it
            }
        }
        val modelFeatures = RhythmFeatures.of(
            averageBpm,
            rrRuns,
            premature,
            differentShape,
            pRelative,
            // Same definition as when the model's training features were exported.
            rules.note == EcgNote.IRREGULAR_PATTERN || (features != null && features.isIrregular && RhythmClassifier.patterned(rrRuns)),
            noise,
            (usable / duration.coerceAtLeast(1f)).toDouble(),
            validFraction,
            assessed.map { it.second },
            pauses,
            beats.qrsWidthMs.firstOrNull() ?: 0.0,
            beats.templateRangeMv
        )
        val model = RhythmModel.bundled
        val probabilities = model?.predict(modelFeatures)
        var finalReason = reason
        val decision = if (model == null || probabilities == null || reason != EcgPoorReason.NONE || averageBpm == null) {
            rules
        } else {
            decide(model, probabilities, averageBpm, rules).also {
                if (it.result ==
                    EcgResult.POOR_RECORDING
                ) {
                    finalReason = if (motion > muscle) EcgPoorReason.MOTION else EcgPoorReason.MUSCLE_NOISE
                }
            }
        }
        val evidence = RhythmClassifier.Evidence(
            quality / 100.0,
            features,
            pWave,
            pRelative,
            premature.size,
            ventricularLike,
            rules.note == EcgNote.IRREGULAR_PATTERN,
            probabilities?.toList()
        )

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
            poorReason = finalReason,
            noisySeconds = seconds.indices.filter { !seconds[it].usable },
            sampleRateHz = session.measuredRateHz ?: fs.toFloat(),
            inverted = beats.inverted,
            algorithm = ALGORITHM,
            // In AFib every interval is irregular: "early beats" isn't a meaningful count there.
            ectopicBeats = if (decision.result == EcgResult.AFIB_SIGNS) 0 else premature.size,
            ventricularLikeBeats = if (decision.result == EcgResult.AFIB_SIGNS) 0 else ventricularLike,
            pauses = pauses,
            longestPauseMs = longestPause?.roundToInt(),
            segments = segments.count { it.count() >= fs },
            note = if (finalReason == EcgPoorReason.NONE) decision.note else EcgNote.NONE
        )
        return EcgAnalysis(
            peaks, averageBpm, decision.result, evidence, metrics,
            seconds.zip(
                assessed.map {
                    it.second
                }
            ),
            rrRuns, noise, IntArray(peaks.size) { if (beats.isOtherShape(it)) 1 else 0 }, modelFeatures, reason
        )
    }

    /**
     * The learned model's decision (mirrors decide() in tools/ecg-eval/train_rhythm.py, whose
     * cross-validated results are in docs/algorithms/ECG_ALGORITHM.md). The rules' note explains the result.
     */
    fun decide(model: RhythmModel, p: DoubleArray, bpm: Int, rules: RhythmClassifier.Decision): RhythmClassifier.Decision {
        val t = model.thresholds
        val cls = { name: String -> p[model.classes.indexOf(name)] }
        val note = rules.note
        return when {
            cls("noisy") >= (t["noisy"] ?: 0.5) -> RhythmClassifier.Decision(EcgResult.POOR_RECORDING, EcgNote.NONE)
            cls(
                "af"
            ) >= (t["af"] ?: 0.5) &&
                bpm <= RhythmClassifier.AF_MAX_HR -> RhythmClassifier.Decision(EcgResult.AFIB_SIGNS, EcgNote.NONE)
            bpm > RhythmClassifier.HIGH_HR -> RhythmClassifier.Decision(
                EcgResult.HIGH_HEART_RATE,
                if (bpm >
                    RhythmClassifier.AF_MAX_HR
                ) {
                    EcgNote.RATE_ABOVE_150
                } else {
                    note
                }
            )
            bpm < RhythmClassifier.LOW_HR -> RhythmClassifier.Decision(EcgResult.LOW_HEART_RATE, note)
            cls("normal") >= (t["normal"] ?: 0.5) && bpm <= RhythmClassifier.SINUS_MAX_HR ->
                RhythmClassifier.Decision(
                    EcgResult.SINUS_RHYTHM,
                    note.takeIf { it == EcgNote.EXTRA_BEATS || it == EcgNote.PAUSES } ?: EcgNote.NONE
                )
            else -> RhythmClassifier.Decision(
                EcgResult.INCONCLUSIVE,
                when {
                    note != EcgNote.NONE -> note
                    bpm > RhythmClassifier.SINUS_MAX_HR -> EcgNote.FAST_REGULAR
                    else -> EcgNote.IRREGULAR_PATTERN
                }
            )
        }
    }

    /** Contiguous stretches of the recording (the recorder splices contact periods together). */
    internal fun segments(starts: List<Int>, size: Int): List<IntRange> {
        val s = (listOf(0) + starts).filter { it in 0 until size }.distinct().sorted()
        return s.mapIndexed { i, from -> from until (s.getOrNull(i + 1) ?: size) }.filter { !it.isEmpty() }
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
