package com.heartline.shared.ecg

/**
 * Collects a fixed-length recording from streamed chunks. Only samples taken while the
 * electrodes are in contact count towards the target (like SHM, which pauses the countdown), and
 * the first [settleSeconds] after every touch are skipped: the electrode/skin interface settles
 * with a large drift that is not ECG. Lead-off time only counts once the finger first touched.
 */
class EcgRecorder(
    val sampleRateHz: Int = 500,
    val targetSeconds: Int = 30,
    val maxLeadOffSeconds: Int = 10,
    val settleSeconds: Double = 1.0
) {
    private val target = sampleRateHz * targetSeconds
    private val settleSamples = (settleSeconds * sampleRateHz).toInt()
    private val buffer = FloatArray(target)
    private var collected = 0
    private var leadOffSamples = 0
    private var consecutiveLeadOff = 0
    private var settling = 0
    private var touched = false

    var leadOff = true
        private set

    /** True while the signal right after a touch is being skipped (show the trace, don't count it). */
    val isSettling: Boolean get() = settling > 0

    val progress: Float get() = collected.toFloat() / target
    val secondsLeft: Int get() = ((target - collected + sampleRateHz - 1) / sampleRateHz)
    val isComplete: Boolean get() = collected >= target

    /** True once contact has been lost for too long in a row; the session should be abandoned. */
    val isAbandoned: Boolean get() = consecutiveLeadOff >= maxLeadOffSeconds * sampleRateHz

    val leadOffRatio: Float get() = if (collected + leadOffSamples == 0) 0f else leadOffSamples.toFloat() / (collected + leadOffSamples)

    val leadOffSeconds: Float get() = leadOffSamples.toFloat() / sampleRateHz

    /** The most recent samples as they arrive (settling included), for the live trace. */
    private val live = FloatArray(sampleRateHz * 4)
    private var liveCount = 0

    fun accept(samples: FloatArray, leadOff: Boolean) {
        if (!leadOff && this.leadOff) settling = settleSamples
        this.leadOff = leadOff
        samples.forEach { v ->
            live[liveCount % live.size] = v
            liveCount++
        }
        if (leadOff) {
            // Before the first touch the user is still getting ready: not a contact problem.
            if (touched) {
                leadOffSamples += samples.size
                consecutiveLeadOff += samples.size
            }
            return
        }
        touched = true
        consecutiveLeadOff = 0
        var from = 0
        if (settling > 0) {
            from = minOf(settling, samples.size)
            settling -= from
        }
        val n = minOf(samples.size - from, target - collected)
        if (n <= 0) return
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
}
