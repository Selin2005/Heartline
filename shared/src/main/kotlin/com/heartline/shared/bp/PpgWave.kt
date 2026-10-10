// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.bp

import kotlinx.serialization.Serializable

/**
 * The wavelength a quick session's pulse wave (the PWA_GREEN channel's wave, [BpSessionInput.green])
 * comes from. Green on every watch that gives it; infrared on a watch whose PPG_ON_DEMAND gives no
 * green (algorithm 6.6, see [PpgWaveChoice]).
 */
@Serializable
enum class PpgWave { GREEN, IR }

/**
 * Picks the pulse wave of a quick session (algorithm 6.6). A real Galaxy Watch7 (SM-L310) gave
 * green, infrared and red from PPG_ON_DEMAND but every green point had status −1 and a value
 * around 0 (noise of ±100 without any offset), while the infrared had a clean pulse. The green
 * LED itself worked (the PPG inside ECG had real values). Recording waited for a steady green
 * pulse that never came, and no calibration round could ever be taken.
 *
 * The infrared becomes the wave only when all three hold, so a watch with a working green never
 * changes wave: the watch said the green is missing (status −1 on [GREEN_OFF_SHARE] of the
 * points), the green has no usable pulse, and there is an infrared wave. The calibration keeps
 * the wave it was taken with ([CalibrationPoint.wave]): a measurement on the other wave is never
 * compared with it.
 */
object PpgWaveChoice {
    /** Share of the points with green status −1 from which the watch is taken to give no green. */
    const val GREEN_OFF_SHARE = 0.9

    /** Status the SDK gives a channel it didn't measure (and the session log for a missing one). */
    const val STATUS_OFF = -1

    /** Share of [greenStatus] values that are [STATUS_OFF] (0 without any). */
    fun greenOffShare(greenStatus: FloatArray): Double =
        if (greenStatus.isEmpty()) 0.0 else greenStatus.count { it.toInt() == STATUS_OFF }.toDouble() / greenStatus.size

    fun choose(green: FloatArray, ir: FloatArray?, fs: Int, greenOffShare: Double): PpgWave {
        if (greenOffShare < GREEN_OFF_SHARE) return PpgWave.GREEN
        if (ir == null || ir.isEmpty() || ir.count { it.isFinite() } < ir.size * 0.9) return PpgWave.GREEN
        val greenPulse = PpgFeatures.extract(green, fs)
        if (greenPulse != null && greenPulse.quality >= BpEstimator.MIN_QUALITY && greenPulse.beats >= BpEstimator.MIN_BEATS) {
            return PpgWave.GREEN
        }
        return PpgWave.IR
    }

    /** [input] with the infrared as its pulse wave when [choose] says so (and no separate IR channel then). */
    fun apply(input: BpSessionInput, greenOffShare: Double): BpSessionInput {
        if (input.precise != null || input.wave != PpgWave.GREEN) return input
        val ir = input.ir ?: return input
        if (choose(input.green, ir, input.fs, greenOffShare) != PpgWave.IR) return input
        return input.copy(green = ir, ir = null, wave = PpgWave.IR)
    }
}
