// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.ecg

import kotlin.math.abs

/**
 * Contact phases of a recording:
 * - [WAITING]: no finger yet;
 * - [ARMING]: the watch reports contact; the electrode is settling and the signal is being checked
 *   (it must look like an ECG) before anything counts;
 * - [RECORDING]: counting towards the target;
 * - [PAUSED]: contact was lost after recording started; counting stops until the finger is back
 *   and the signal is checked again.
 */
enum class ContactPhase { WAITING, ARMING, RECORDING, PAUSED }

/**
 * Collects a fixed-length recording from streamed chunks (algorithm 3, docs/algorithms/ECG_ALGORITHM.md).
 *
 * Nothing is counted on the SDK's contact flag alone. Contact must hold for [contactDebounceMs];
 * then the first [settleSeconds] (electrode/skin settling) are dropped; then the last
 * [verifySeconds] of signal must look like an ECG ([verify]) before the countdown starts, and those
 * verified seconds are kept. If the SDK has reported contact without a break for
 * [verifyFallbackSeconds] and the check still fails, a lenient check is used instead (an unusual
 * wrist ECG, e.g. extra beats of another height, must not block the recording for good; flat
 * signal, noise, drift and hum still don't pass). The analysis judges the quality afterwards.
 * A lift shorter than [liftDebounceMs] doesn't pause the countdown, but
 * any skipped samples end the current segment: [segmentStarts] marks where the recording was
 * spliced, so no interval is ever measured across a gap.
 */
class EcgRecorder(
    val sampleRateHz: Int = 500,
    val targetSeconds: Int = 30,
    val maxLeadOffSeconds: Int = 10,
    val settleSeconds: Double = 1.0,
    val contactDebounceMs: Int = 500,
    val liftDebounceMs: Int = 300,
    val verifySeconds: Double = 3.0,
    val verifyFallbackSeconds: Double = 6.0,
    /** (window, lenient) → does it look like an ECG? Null: [EcgContactCheck.diagnose], kept in [lastCheck]. */
    private val verify: ((FloatArray, Boolean) -> Boolean)? = null
) {
    private val target = sampleRateHz * targetSeconds
    private val buffer = FloatArray(target)
    private var collected = 0
    private var leadOffSamples = 0
    private var consecutiveLeadOff = 0
    private var contactRun = 0
    private var liftRun = 0
    private var settleLeft = 0
    private val verifyWindow = (verifySeconds * sampleRateHz).toInt()
    private val arming = ArrayDeque<Float>()
    private var armedSamples = 0
    private val starts = mutableListOf<Int>()
    private var needsNewSegment = true

    var phase = ContactPhase.WAITING
        private set

    /** The latest ECG-shape check while arming (for diagnostics logs). */
    var lastCheck: EcgContactCheck.Diagnosis? = null
        private set

    /** Seconds of signal received since contact was confirmed (arming), for diagnostics. */
    val armedSeconds: Double get() = armedSamples.toDouble() / sampleRateHz

    /** Counting isn't running (no contact, or contact not yet confirmed). */
    val leadOff: Boolean get() = phase != ContactPhase.RECORDING

    /** True while contact is reported but the signal is settling or being checked. */
    val isSettling: Boolean get() = phase == ContactPhase.ARMING

    /** Contact has been reported for a while but the signal still doesn't look like an ECG. */
    val isStruggling: Boolean get() = phase == ContactPhase.ARMING && armedSamples > sampleRateHz * STRUGGLE_SECONDS

    val progress: Float get() = collected.toFloat() / target
    val secondsLeft: Int get() = ((target - collected + sampleRateHz - 1) / sampleRateHz)
    val isComplete: Boolean get() = collected >= target

    /** True once contact has been lost for too long in a row after recording started; the session should be abandoned. */
    val isAbandoned: Boolean get() = consecutiveLeadOff >= maxLeadOffSeconds * sampleRateHz

    val leadOffRatio: Float get() = if (collected + leadOffSamples == 0) 0f else leadOffSamples.toFloat() / (collected + leadOffSamples)

    val leadOffSeconds: Float get() = leadOffSamples.toFloat() / sampleRateHz

    /** Start index (in [recording]) of each contiguous segment. */
    val segmentStarts: List<Int> get() = starts.toList()

    /** The most recent samples as they arrive, for the live trace. */
    private val live = FloatArray(sampleRateHz * 4)
    private var liveCount = 0

    /**
     * @param leadOff the SDK reports no contact.
     * @param saturated samples beyond the sensor's thresholds: treated like no contact.
     */
    fun accept(samples: FloatArray, leadOff: Boolean, saturated: Boolean = false) {
        samples.forEach { v ->
            live[liveCount % live.size] = v
            liveCount++
        }
        val contact = !leadOff && !saturated
        if (contact) {
            contactRun += samples.size
            liftRun = 0
        } else {
            liftRun += samples.size
            contactRun = 0
        }
        when (phase) {
            ContactPhase.WAITING, ContactPhase.PAUSED -> {
                if (!contact) countLeadOff(samples.size)
                if (contactRun >= contactDebounceMs * sampleRateHz / 1000) startArming()
            }
            ContactPhase.ARMING -> {
                if (!contact) {
                    countLeadOff(samples.size)
                    if (liftRun >=
                        liftDebounceMs * sampleRateHz / 1000
                    ) {
                        phase = if (starts.isEmpty()) ContactPhase.WAITING else ContactPhase.PAUSED
                    }
                    arming.clear()
                    return
                }
                // Once recording has started, time spent re-checking a poor contact counts as lost contact.
                if (starts.isNotEmpty()) consecutiveLeadOff += samples.size else consecutiveLeadOff = 0
                arm(samples)
            }
            ContactPhase.RECORDING -> {
                if (!contact) {
                    needsNewSegment = true
                    if (liftRun >= liftDebounceMs * sampleRateHz / 1000) {
                        phase = ContactPhase.PAUSED
                        countLeadOff(liftRun)
                    }
                    return
                }
                consecutiveLeadOff = 0
                append(samples, 0)
            }
        }
    }

    private fun countLeadOff(n: Int) {
        if (starts.isEmpty() && phase != ContactPhase.PAUSED) return // before the first recording: the user is getting ready
        leadOffSamples += n
        consecutiveLeadOff += n
    }

    private fun startArming() {
        phase = ContactPhase.ARMING
        settleLeft = (settleSeconds * sampleRateHz).toInt()
        arming.clear()
        armedSamples = 0
    }

    private fun arm(samples: FloatArray) {
        var from = 0
        if (settleLeft > 0) {
            from = minOf(settleLeft, samples.size)
            settleLeft -= from
        }
        for (i in from until samples.size) {
            arming.addLast(samples[i])
            if (arming.size > verifyWindow) arming.removeFirst()
        }
        armedSamples += samples.size
        if (arming.size < verifyWindow) return
        val window = arming.toFloatArray()
        val lenient = armedSamples >= verifyFallbackSeconds * sampleRateHz
        val ok = verify?.invoke(window, lenient) ?: EcgContactCheck.diagnose(window, sampleRateHz, lenient).also { lastCheck = it }.accepted
        if (!ok) return
        phase = ContactPhase.RECORDING
        needsNewSegment = true
        consecutiveLeadOff = 0
        arming.clear()
        append(window, 0)
    }

    private fun append(samples: FloatArray, from: Int) {
        val n = minOf(samples.size - from, target - collected)
        if (n <= 0) return
        if (needsNewSegment) {
            starts += collected
            needsNewSegment = false
        }
        samples.copyInto(buffer, collected, from, from + n)
        collected += n
    }

    /** The last [seconds] of collected signal. */
    fun recent(seconds: Double = 3.0): FloatArray {
        val n = (seconds * sampleRateHz).toInt().coerceAtMost(collected)
        return buffer.copyOfRange(collected - n, collected)
    }

    /** The last [seconds] of everything received (including settling and lead-off), for the live strip. */
    fun live(seconds: Double = 3.0): FloatArray {
        val n = (seconds * sampleRateHz).toInt().coerceAtMost(minOf(liveCount, live.size))
        return FloatArray(n) { k -> live[(liveCount - n + k) % live.size] }
    }

    fun recording(): FloatArray = buffer.copyOf(collected)

    private companion object {
        const val STRUGGLE_SECONDS = 4
    }
}

/** Does a short window of raw contact signal look like an ECG (and not like skin noise or a loose touch)? */
object EcgContactCheck {
    const val MIN_P2P_MV = 0.05
    const val MAX_P2P_MV = 5.0

    /** What [looksLikeEcg] measured on a window, and the first test it failed ([rejected], null = accepted). */
    data class Diagnosis(
        val p2pMv: Double?,
        val kurtosis: Double?,
        val peaks: Int,
        val rrMs: List<Int>,
        val heightRatio: Double?,
        val lenient: Boolean,
        val rejected: String?
    ) {
        val accepted: Boolean get() = rejected == null
    }

    /**
     * @param lenient after a long unbroken contact: kurtosis only ≥ [LENIENT_KURTOSIS] and no
     * R-height check (both can fail on a real wrist ECG with extra beats or a big T wave).
     */
    fun looksLikeEcg(raw: FloatArray, fs: Int, lenient: Boolean = false): Boolean = diagnose(raw, fs, lenient).accepted

    fun diagnose(raw: FloatArray, fs: Int, lenient: Boolean = false): Diagnosis {
        fun no(reason: String, p2p: Double? = null, k: Double? = null, peaks: Int = 0, rr: List<Int> = emptyList(), h: Double? = null) =
            Diagnosis(p2p, k, peaks, rr, h, lenient, reason)
        if (raw.size < 2 * fs) return no("too short")
        if (raw.any { !it.isFinite() }) return no("non-finite samples")
        val clean = EcgFilter.clean(raw, fs)
        // The filters' first and last 200 ms are unreliable on such a short window.
        val edge = (0.2 * fs).toInt()
        val core = clean.copyOfRange(edge, clean.size - edge)
        val p2p = (core.max() - core.min()).toDouble()
        if (p2p < MIN_P2P_MV) return no("amplitude < $MIN_P2P_MV mV", p2p)
        if (p2p > MAX_P2P_MV) return no("amplitude > $MAX_P2P_MV mV", p2p)
        val k = kurtosis(core)
        val minK = if (lenient) LENIENT_KURTOSIS else MIN_KURTOSIS
        if (k < minK) return no("kurtosis < $minK", p2p, k)
        val peaks = RPeakDetector.detect(clean, fs).filter { it in edge until clean.size - edge }
        val rr = peaks.zipWithNext { a, b -> ((b - a) * 1000.0 / fs).toInt() }
        if (peaks.size < 2) return no("fewer than 2 beats", p2p, k, peaks.size, rr)
        if (rr.any { it !in 250..2200 }) return no("beat interval outside 250-2200 ms", p2p, k, peaks.size, rr)
        val heights = peaks.map { abs(clean[it]).toDouble() }
        val ratio = heights.max() / heights.min()
        if (!lenient && ratio > 3) return no("beat heights differ > 3x", p2p, k, peaks.size, rr, ratio)
        return Diagnosis(p2p, k, peaks.size, rr, ratio, lenient, null)
    }

    /** ECG is spiky (kurtosis well above 3); noise and drift are not. */
    const val MIN_KURTOSIS = 4.0

    /** Gaussian noise is 3, drift and hum below 2. */
    const val LENIENT_KURTOSIS = 3.5

    internal fun kurtosis(x: FloatArray): Double {
        val m = x.average()
        var m2 = 0.0
        var m4 = 0.0
        for (v in x) {
            val d = v - m
            m2 += d * d
            m4 += d * d * d * d
        }
        m2 /= x.size
        m4 /= x.size
        return if (m2 <= 1e-12) 0.0 else m4 / (m2 * m2)
    }
}
