// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary waveform payload: 16-byte header (magic "HLW1", kind ordinal, sample rate, sample
 * count) followed by little-endian Float32 samples. ~60 KB for 30 s of ECG at 500 Hz.
 */
object WaveCodec {
    private const val MAGIC = 0x31574C48 // "HLW1" little-endian
    const val HEADER_BYTES = 16

    data class Wave(val kindOrdinal: Int, val sampleRateHz: Int, val samples: FloatArray)

    fun encode(kindOrdinal: Int, sampleRateHz: Int, samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(kindOrdinal).putInt(sampleRateHz).putInt(samples.size)
        buffer.asFloatBuffer().put(samples)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): Wave {
        require(bytes.size >= HEADER_BYTES) { "Wave payload too short: ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "Bad wave magic" }
        val kind = buffer.int
        val rate = buffer.int
        val count = buffer.int
        require(count >= 0 && bytes.size == HEADER_BYTES + count * 4) { "Wave length mismatch" }
        val samples = FloatArray(count)
        buffer.asFloatBuffer().get(samples)
        return Wave(kind, rate, samples)
    }
}
