// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heartline.shared.update.GitHubAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** The downloaded update is kept, reused, verified again and cleaned up once installed. */
@RunWith(AndroidJUnit4::class)
class UpdaterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val apkBytes = Random(3).nextBytes(50_000)
    private val apk = GitHubAsset("Heartline-phone-1.3.0.apk", apkBytes.size.toLong(), "https://example.invalid/apk")
    private val sums = GitHubAsset("SHA256SUMS", url = "https://example.invalid/sums")
    private val dir get() = File(context.filesDir, "updates")

    /** Serves [apkBytes] (continuing what the file already has) and a SHA256SUMS naming [sumsSha]. */
    private inner class FakeSource : ReleaseSource("test") {
        var downloads = 0
        var resumedFrom = -1L
        var sumsSha = sha(apkBytes)

        override suspend fun text(url: String) = "$sumsSha  ${apk.name}\n"

        override suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): String {
            downloads++
            resumedFrom = if (target.isFile) target.length() else 0
            target.parentFile?.mkdirs()
            target.appendBytes(apkBytes.copyOfRange(resumedFrom.toInt().coerceAtMost(apkBytes.size), apkBytes.size))
            onProgress(target.length(), apkBytes.size.toLong())
            return sha(target.readBytes())
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val repository = UpdateRepository(context, "1.2.0")
    private val source = FakeSource()

    private fun updater(installed: String = "1.2.0") = Updater(context, source, repository, installed, enabled = true)

    @Before
    fun reset() = runBlocking {
        repository.clearReady()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun aVerifiedDownloadIsKeptAsReady() = runBlocking {
        val file = updater().download("1.3.0", apk, sums)
        assertEquals(File(dir, apk.name), file)
        assertArrayEquals(apkBytes, file.readBytes())
        assertFalse(File(dir, "${apk.name}.part").exists())
        assertEquals(UpdateRepository.Ready("1.3.0", apk.name, sha(apkBytes)), repository.current().ready)
    }

    @Test
    fun theReadyUpdateIsInstalledAgainWithoutDownloading() = runBlocking {
        updater().download("1.3.0", apk, sums)
        val again = updater().download("1.3.0", apk, sums)
        assertEquals(1, source.downloads)
        assertArrayEquals(apkBytes, again.readBytes())
        assertNotNull(updater().readyFile())
    }

    @Test
    fun aDamagedReadyFileIsDownloadedAgain() = runBlocking {
        val file = updater().download("1.3.0", apk, sums)
        file.writeBytes(ByteArray(10))
        assertNull(updater().readyFile())
        assertNull(repository.current().ready)
        updater().download("1.3.0", apk, sums)
        assertEquals(2, source.downloads)
        assertArrayEquals(apkBytes, File(dir, apk.name).readBytes())
    }

    @Test
    fun aPartialDownloadContinuesWhereItStopped() = runBlocking {
        dir.mkdirs()
        File(dir, "${apk.name}.part").writeBytes(apkBytes.copyOf(20_000))
        updater().download("1.3.0", apk, sums)
        assertEquals(20_000L, source.resumedFrom)
        assertArrayEquals(apkBytes, File(dir, apk.name).readBytes())
    }

    @Test
    fun otherVersionsFilesAreDeleted() = runBlocking {
        dir.mkdirs()
        val old = File(dir, "Heartline-phone-1.2.5.apk.part").apply { writeBytes(ByteArray(5)) }
        val oldReady = File(dir, "Heartline-phone-1.2.5.apk").apply { writeBytes(ByteArray(5)) }
        updater().download("1.3.0", apk, sums)
        assertFalse(old.exists())
        assertFalse(oldReady.exists())
    }

    @Test
    fun aChecksumMismatchKeepsNothing() = runBlocking {
        source.sumsSha = sha(ByteArray(1))
        try {
            updater().download("1.3.0", apk, sums)
            fail("expected a checksum mismatch")
        } catch (_: Updater.ChecksumMismatch) {
        }
        assertNull(repository.current().ready)
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun theDownloadIsDeletedOnceInstalled() = runBlocking {
        updater().download("1.3.0", apk, sums)
        // Still on 1.2.0: kept.
        updater("1.2.0").cleanUp()
        assertNotNull(repository.current().ready)
        assertTrue(File(dir, apk.name).exists())
        // 1.3.0 installed: deleted.
        updater("1.3.0").cleanUp()
        assertNull(repository.current().ready)
        assertFalse(dir.exists())
    }
}
