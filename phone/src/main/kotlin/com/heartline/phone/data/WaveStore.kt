// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import com.heartline.shared.sync.WaveCodec
import java.io.File

/** Waveforms are kept as files (DB holds only the path) to keep the database small. */
class WaveStore(private val root: File) {
    fun write(id: String, kindOrdinal: Int, sampleRateHz: Int, samples: FloatArray): String {
        val relative = "waves/$id.bin"
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(WaveCodec.encode(kindOrdinal, sampleRateHz, samples))
        tmp.renameTo(file)
        return relative
    }

    fun read(relativePath: String): FloatArray? {
        val file = File(root, relativePath)
        if (!file.exists()) return null
        return runCatching { WaveCodec.decode(file.readBytes()).samples }.getOrNull()
    }

    fun delete(relativePath: String) {
        File(root, relativePath).delete()
    }

    fun deleteAll() {
        File(root, "waves").deleteRecursively()
    }
}
