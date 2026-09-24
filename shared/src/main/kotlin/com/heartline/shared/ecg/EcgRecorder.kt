package com.heartline.shared.ecg

/**
 * Collects a fixed-length recording from streamed chunks. Only samples taken while the
 * electrodes are in contact count towards the target; samples during lead-off are dropped so
 * the stored trace is continuous-contact only (like SHM, which pauses the countdown).
 */
class EcgRecorder(val sampleRateHz: Int = 500, val targetSeconds: Int = 30, val maxLeadOffSeconds: Int = 10) {
    private val target = sampleRateHz * targetSeconds
    private val buffer = FloatArray(target)
    private var collected = 0
    private var leadOffSamples = 0
    private var consecutiveLeadOff = 0

    var leadOff = false
        private set

    val progress: Float get() = collected.toFloat() / target
    val secondsLeft: Int get() = ((target - collected + sampleRateHz - 1) / sampleRateHz)
    val isComplete: Boolean get() = collected >= target

    /** True once contact has been lost for too long in a row; the session should be abandoned. */
    val isAbandoned: Boolean get() = consecutiveLeadOff >= maxLeadOffSeconds * sampleRateHz

    val leadOffRatio: Float get() = if (collected + leadOffSamples == 0) 0f else leadOffSamples.toFloat() / (collected + leadOffSamples)

    fun accept(samples: FloatArray, leadOff: Boolean) {
        this.leadOff = leadOff
        if (leadOff) {
            leadOffSamples += samples.size
            consecutiveLeadOff += samples.size
            return
        }
        consecutiveLeadOff = 0
        val n = minOf(samples.size, target - collected)
        samples.copyInto(buffer, collected, 0, n)
        collected += n
    }

    /** The last [seconds] of collected signal, for the live trace. */
    fun recent(seconds: Double = 3.0): FloatArray {
        val n = (seconds * sampleRateHz).toInt().coerceAtMost(collected)
        return buffer.copyOfRange(collected - n, collected)
    }

    fun recording(): FloatArray = buffer.copyOf(collected)
}
