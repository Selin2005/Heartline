// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.AppInfo
import com.heartline.shared.update.Release
import com.heartline.shared.update.Releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Downloads [url] into [target], continuing a partial [target] from where it stopped (an HTTP
     * `Range` request), and returns the whole file's SHA-256 (lowercase hex). [onProgress] gets
     * the bytes on disk and the total (0 when unknown).
     *
     * - 206: the server continues; the rest is appended.
     * - 200: the server ignored the range; the file is written again from the start.
     * - 416: nothing left to send (the file is already complete); it is only hashed.
     */
    open suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): String = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val have = if (target.isFile) target.length() else 0L
        val connection = open(url, accept = "application/octet-stream", from = have)
        try {
            val code = connection.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL
            val length = connection.contentLengthLong
            val total = when {
                code == RANGE_NOT_SATISFIABLE -> have
                append -> totalOf(connection.getHeaderField("Content-Range")) ?: if (length > 0) have + length else 0L
                else -> length.coerceAtLeast(0L)
            }
            HLog.i(TAG, "download ${target.name}: had $have bytes, HTTP $code, ${if (append) "resuming" else if (code == RANGE_NOT_SATISFIABLE) "complete" else "from the start"}, total $total")
            if (code != RANGE_NOT_SATISFIABLE) {
                connection.inputStream.use { input ->
                    FileOutputStream(target, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = if (append) have else 0L
                        onProgress(done, total)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        sha256(target)
    }

    private fun open(url: String, accept: String = "application/vnd.github+json", from: Long = 0): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("User-Agent", userAgent)
        if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
        val code = connection.responseCode
        if (code !in 200..299 && !(from > 0 && code == RANGE_NOT_SATISFIABLE)) {
            connection.disconnect()
            throw IOException("HTTP $code for $url")
        }
        return connection
    }

    companion object {
        private const val TAG = "Heartline/Update"
        private const val RANGE_NOT_SATISFIABLE = 416

        /** The total size from a `Content-Range: bytes 100-199/200` header. */
        internal fun totalOf(contentRange: String?): Long? = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

        /** The SHA-256 of [file] (lowercase hex). */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        const val RELEASES_API = "https://api.github.com/repos/${AppInfo.REPO_OWNER}/${AppInfo.REPO_NAME}/releases?per_page=30"
    }
}
