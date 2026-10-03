// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random

/** The update download continues a partial file (HTTP Range) and hashes the whole file. */
@RunWith(AndroidJUnit4::class)
class ReleaseSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val apk = Random(7).nextBytes(300_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer

    /** Whether the server honours Range; the Range headers it was sent. */
    private var ranges = true
    private val asked = mutableListOf<String?>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            asked += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            when {
                from == null || !ranges -> {
                    exchange.sendResponseHeaders(200, apk.size.toLong())
                    exchange.responseBody.use { it.write(apk) }
                }
                from >= apk.size -> {
                    exchange.responseHeaders.add("Content-Range", "bytes */${apk.size}")
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                }
                else -> {
                    exchange.responseHeaders.add("Content-Range", "bytes $from-${apk.size - 1}/${apk.size}")
                    exchange.sendResponseHeaders(206, (apk.size - from).toLong())
                    exchange.responseBody.use { it.write(apk, from, apk.size - from) }
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/apk"
    private val source = ReleaseSource("test")

    @Test
    fun downloadsAWholeFile() = runBlocking {
        val target = folder.root.resolve("a.apk.part")
        var last = 0L to 0L
        val hash = source.download(url, target) { done, total -> last = done to total }
        assertEquals(sha, hash)
        assertArrayEquals(apk, target.readBytes())
        assertEquals(apk.size.toLong() to apk.size.toLong(), last)
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun continuesAPartialFileWithARangeRequest() = runBlocking {
        val target = folder.root.resolve("a.apk.part").apply { writeBytes(apk.copyOf(120_000)) }
        var first: Pair<Long, Long>? = null
        val hash = source.download(url, target) { done, total -> if (first == null) first = done to total }
        assertEquals(listOf<String?>("bytes=120000-"), asked)
        // Progress starts from what was already there, out of the whole size.
        assertEquals(120_000L to apk.size.toLong(), first)
        assertEquals(sha, hash)
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun startsAgainWhenTheServerIgnoresTheRange() = runBlocking {
        ranges = false
        val target = folder.root.resolve("a.apk.part").apply { writeBytes(apk.copyOf(120_000)) }
        assertEquals(sha, source.download(url, target))
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun aCompleteFileIsOnlyHashed() = runBlocking {
        val target = folder.root.resolve("a.apk.part").apply { writeBytes(apk) }
        assertEquals(sha, source.download(url, target))
        assertEquals(listOf<String?>("bytes=${apk.size}-"), asked)
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun aDamagedPartialFileGivesAnotherHash() = runBlocking {
        val target = folder.root.resolve("a.apk.part").apply { writeBytes(ByteArray(120_000)) }
        // Continuing a wrong start can't pass: the caller compares against SHA256SUMS and deletes it.
        assert(source.download(url, target) != sha)
    }

    @Test
    fun totalFromContentRange() {
        assertEquals(200L, ReleaseSource.totalOf("bytes 100-199/200"))
        assertNull(ReleaseSource.totalOf("bytes 100-199/*"))
        assertNull(ReleaseSource.totalOf(null))
    }
}
