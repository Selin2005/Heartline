// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URL

class ReleaseSourceSecurityTest {
    private val source = ReleaseSource("HeartlineTest/1.0")

    @Test
    fun `rejects cleartext http urls`() {
        assertThrows(IOException::class.java) {
            runBlocking { source.text("http://api.github.com/repos/example/repo/releases") }
        }
    }

    @Test
    fun `rejects non github hosts`() {
        assertThrows(IOException::class.java) {
            runBlocking { source.download("https://evil.example.com/app.apk", File.createTempFile("test", ".apk")) }
        }
    }

    @Test
    fun `rejects non release githubusercontent hosts and allows release asset hosts`() {
        assertFalse(source.isAllowedUpdateUrl(URL("https://raw.githubusercontent.com/org/repo/main/app.apk")))
        assertFalse(source.isAllowedUpdateUrl(URL("https://gist.githubusercontent.com/user/123/raw/app.apk")))
        assertTrue(source.isAllowedUpdateUrl(URL("https://objects.githubusercontent.com/github-production-release-asset/1")))
        assertTrue(source.isAllowedUpdateUrl(URL("https://release-assets.githubusercontent.com/github-production-release-asset/1")))
    }
}
