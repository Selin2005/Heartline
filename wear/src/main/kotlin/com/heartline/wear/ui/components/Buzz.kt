// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Distinct vibrations, so the wrist tells what happened without looking: a quick double tap when a
 * measurement starts, one soft long pulse when it is done, three firm pulses when a result needs
 * attention.
 */
enum class Buzz(val timings: LongArray, val amplitudes: IntArray) {
    STARTED(longArrayOf(0, 30, 70, 30), intArrayOf(0, 160, 0, 160)),
    DONE(longArrayOf(0, 180), intArrayOf(0, 110)),
    ATTENTION(longArrayOf(0, 90, 90, 90, 90, 90), intArrayOf(0, 255, 0, 255, 0, 255)),
    /** A soft tick between breathing in and out. */
    BREATH(longArrayOf(0, 40), intArrayOf(0, 70)),
    CELEBRATE(longArrayOf(0, 25, 50, 25, 50, 25, 50, 120), intArrayOf(0, 120, 0, 160, 0, 200, 0, 255)),
    ;

    fun play(context: Context) {
        val vibrator = runCatching { context.getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull()
            ?: context.getSystemService(Vibrator::class.java)
            ?: return
        if (!vibrator.hasVibrator()) return
        val amplitudes = if (vibrator.hasAmplitudeControl()) amplitudes else IntArray(amplitudes.size) { if (it % 2 == 1) VibrationEffect.DEFAULT_AMPLITUDE else 0 }
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }
}

/** Plays a [Buzz] (no-op when [enabled] is false: the user turned vibration feedback off). */
@Composable
fun rememberBuzz(enabled: Boolean): (Buzz) -> Unit {
    val context = LocalContext.current
    return remember(context, enabled) { { buzz -> if (enabled) buzz.play(context) } }
}
