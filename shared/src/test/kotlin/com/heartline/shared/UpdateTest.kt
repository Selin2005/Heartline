// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared

import com.heartline.shared.update.AppVersion
import com.heartline.shared.update.AppVersion.Channel.BETA
import com.heartline.shared.update.AppVersion.Channel.DEV
import com.heartline.shared.update.AppVersion.Channel.STABLE
import com.heartline.shared.update.GitHubAsset
import com.heartline.shared.update.GitHubRelease
import com.heartline.shared.update.Releases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private fun v(s: String) = AppVersion.parse(s)!!

    @Test
    fun parsesTags() {
        assertEquals(AppVersion(1, 2, 0), v("v1.2"))
        assertEquals(AppVersion(1, 2, 3, listOf("beta", "4")), v("1.2.3-beta.4+run.99"))
        assertNull(AppVersion.parse("latest"))
        assertEquals(AppVersion.Channel.DEV, v("1.0.0-dev.3").channel)
        assertEquals(AppVersion.Channel.BETA, v("1.0.0-rc.1").channel)
        assertEquals(AppVersion.Channel.STABLE, v("1.0.0").channel)
    }

    @Test
    fun fourPartVersions() {
        assertEquals(AppVersion(0, 0, 2, build = 102), v("v0.0.2.102"))
        assertEquals("0.0.2.102-dev.57", v("0.0.2.102-dev.57").toString())
        assertEquals(AppVersion.Channel.DEV, v("0.0.2.102-dev.57").channel)
        assertEquals(AppVersion.Channel.BETA, v("0.0.2.102-beta.1").channel)
        val sorted = "0.0.2 0.0.2.101 0.0.2.102-beta.1 0.0.2.102 0.0.2.103 0.0.3".split(" ")
        assertEquals(sorted, sorted.reversed().map(::v).sorted().map { it.toString() })
        assertEquals("0.0.2", v("0.0.2").toString())
    }

    @Test
    fun semverOrdering() {
        val sorted = "1.0.0-alpha 1.0.0-alpha.1 1.0.0-beta.2 1.0.0-beta.10 1.0.0-rc.1 1.0.0 1.0.1 1.1.0-beta.1 1.1.0 2.0.0".split(" ")
        assertEquals(sorted, sorted.shuffled(java.util.Random(4)).map(::v).sorted().map { it.toString() })
    }

    private val sample = """
        [
          {"tag_name":"v1.2.0-dev.9","prerelease":true,"draft":false,"body":"dev","html_url":"u","assets":[{"name":"Heartline-phone-1.2.0-dev.9.apk","browser_download_url":"d9"}]},
          {"tag_name":"v1.2.0-beta.2","prerelease":true,"body":"## New\n- b2","html_url":"https://github.com/r/releases/tag/v1.2.0-beta.2",
           "assets":[{"name":"Heartline-phone-1.2.0-beta.2.apk","size":10,"browser_download_url":"p"},{"name":"Heartline-watch-1.2.0-beta.2.apk","browser_download_url":"w"},{"name":"SHA256SUMS","browser_download_url":"s"}],"extra":1},
          {"tag_name":"v1.1.0","prerelease":false,"body":"stable","assets":[{"name":"Heartline-phone-1.1.0.apk","browser_download_url":"p1"},{"name":"Heartline-watch-1.1.0.apk","browser_download_url":"w1"}]},
          {"tag_name":"v1.3.0","draft":true,"assets":[{"name":"Heartline-phone-1.3.0.apk","browser_download_url":"x"}]},
          {"tag_name":"v1.0.0","prerelease":false,"assets":[]}
        ]
    """.trimIndent()

    @Test
    fun picksUpdatesByChannel() {
        val releases = Releases.parse(sample)
        assertEquals(4, releases.size) // the draft is dropped
        assertEquals("1.1.0", Releases.update(releases, "1.0.0", STABLE)!!.version.toString())
        val beta = Releases.update(releases, "1.0.0", BETA)!!
        assertEquals("1.2.0-beta.2", beta.version.toString())
        assertTrue(beta.isBeta)
        assertEquals("p", beta.phoneApk!!.url)
        assertEquals("s", beta.checksums!!.url)
        assertNull(Releases.update(releases, "1.1.0", STABLE))
        assertNull(Releases.update(releases, "1.2.0-beta.2", BETA))
        // A beta user who switches to stable only gets the next stable that is newer than their beta.
        assertNull(Releases.update(releases, "1.2.0-beta.1", STABLE))
        // Stable and beta tracks never get dev builds.
        assertTrue(listOf(STABLE, BETA).none { Releases.update(releases, "1.2.0-dev.3", it)?.version?.isDev == true })
    }

    private fun release(tag: String, day: Int) = GitHubRelease(
        tag = tag,
        prerelease = '-' in tag,
        publishedAt = "2026-10-%02dT10:00:00Z".format(day),
        assets = listOf(GitHubAsset("Heartline-phone-${tag.drop(1)}.apk", url = "u"))
    )

    /** Published in this order, one per day. */
    private val timeline = listOf(
        "v0.0.2.102-dev.57",
        "v0.0.2.102-beta.1",
        "v0.0.2.102-dev.60",
        "v0.0.2.102",
        "v0.0.2.103-beta.1",
        "v0.0.2.103-dev.70",
        "v0.0.2.101"
    ).mapIndexedNotNull { i, t -> Releases.toRelease(release(t, i + 1)) }

    private fun next(installed: String, track: AppVersion.Channel) = Releases.update(timeline, installed, track)?.version?.toString()

    @Test
    fun devTrackGetsEverythingNewer() {
        assertEquals("0.0.2.103-dev.70", next("0.0.2.102-dev.57", DEV))
        // A dev user who updated to a beta or stable stays on the dev track.
        assertEquals("0.0.2.103-dev.70", next("0.0.2.102-beta.1", DEV))
        assertEquals("0.0.2.103-dev.70", next("0.0.2.102", DEV))
        assertNull(next("0.0.2.103-dev.70", DEV))
        // Only the late hotfix for 0.0.2.101 is newer by date, and it isn't offered (older version).
        assertNull(next("0.0.2.103-dev.70", DEV))
        // A local build that isn't a release: version order, dev before the betas of its version.
        assertEquals("0.0.2.103-dev.70", next("0.0.2.103-dev.1", DEV))
    }

    @Test
    fun betaTrackGetsBetasAndStables() {
        assertEquals("0.0.2.103-beta.1", next("0.0.2.102-beta.1", BETA))
        assertEquals("0.0.2.103-beta.1", next("0.0.2.102", BETA))
        assertNull(next("0.0.2.103-beta.1", BETA))
    }

    @Test
    fun stableTrackGetsOnlyStables() {
        assertEquals("0.0.2.102", next("0.0.2.101", STABLE))
        assertEquals("0.0.2.102", next("0.0.2.100", STABLE))
        assertNull(next("0.0.2.102", STABLE))
        assertEquals("0.0.2.102", Releases.newest(timeline, STABLE)!!.version.toString())
        assertEquals("0.0.2.101", Releases.newest(timeline, DEV)!!.version.toString()) // newest by date
    }

    @Test
    fun watchBehind() {
        val latest = Releases.update(Releases.parse(sample), "1.0.0", STABLE)
        assertTrue(Releases.watchBehind("1.0.0", latest))
        assertFalse(Releases.watchBehind("1.1.0", latest))
        assertFalse(Releases.watchBehind(null, latest))
    }

    @Test
    fun checksums() {
        val hash = "a".repeat(64)
        val map = Releases.parseChecksums("$hash  Heartline-phone-1.1.0.apk\n${"B".repeat(64)} *Heartline-watch-1.1.0.apk\ngarbage\n")
        assertEquals(hash, map["Heartline-phone-1.1.0.apk"])
        assertEquals("b".repeat(64), map["Heartline-watch-1.1.0.apk"])
        assertEquals(2, map.size)
    }
}
