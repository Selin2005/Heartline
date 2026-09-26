// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.dsp

/** Min/max-preserving decimation so R peaks survive downsampling. */
fun decimate(samples: FloatArray, target: Int): FloatArray {
    if (samples.size <= target) return samples
    val bucket = samples.size.toFloat() / (target / 2)
    val out = FloatArray((target / 2) * 2)
    for (b in 0 until target / 2) {
        val from = (b * bucket).toInt()
        val to = ((b + 1) * bucket).toInt().coerceAtMost(samples.size)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var mnI = from
        var mxI = from
        for (i in from until to) {
            if (samples[i] < mn) {
                mn = samples[i]
                mnI = i
            }
            if (samples[i] > mx) {
                mx = samples[i]
                mxI = i
            }
        }
        out[2 * b] = if (mnI < mxI) mn else mx
        out[2 * b + 1] = if (mnI < mxI) mx else mn
    }
    return out
}
