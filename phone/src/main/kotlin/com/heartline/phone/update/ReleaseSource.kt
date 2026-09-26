// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import com.heartline.shared.AppInfo
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Reads the project's releases from the GitHub API and downloads release files. No personal data is sent. */
open class ReleaseSource(private val userAgent: String) {
    open suspend fun releases(): List<Release> = Releases.parse(text(RELEASES_API))

    open suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        open(url).run {
            try {
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }
    }

    /** Downloads [url] into [target], reporting progress 0..1; returns the file's SHA-256 (lowercase hex). */
    open suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", userAgent)
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("HTTP $code for $url")
        }
        return connection
    }

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/${AppInfo.REPO_OWNER}/${AppInfo.REPO_NAME}/releases?per_page=30"
    }
}
