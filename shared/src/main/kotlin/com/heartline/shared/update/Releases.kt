// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.update

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The fields of a GitHub release (GET /repos/{owner}/{repo}/releases) that the updater reads. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(val name: String, val size: Long = 0, @SerialName("browser_download_url") val url: String)

/** A release the app can install: its version, notes and the phone / watch APKs and checksums. */
data class Release(
    val version: AppVersion,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String?,
    val phoneApk: GitHubAsset?,
    val watchApk: GitHubAsset?,
    val checksums: GitHubAsset?
) {
    val isBeta: Boolean get() = version.channel == AppVersion.Channel.BETA
}

/**
 * Chooses updates from the project's GitHub releases. Release assets are named by the Build
 * workflow: `Heartline-phone-<version>.apk`, `Heartline-watch-<version>.apk` and `SHA256SUMS`.
 */
object Releases {
    const val PHONE_PREFIX = "Heartline-phone-"
    const val WATCH_PREFIX = "Heartline-watch-"
    const val CHECKSUMS = "SHA256SUMS"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(body: String): List<Release> = json.decodeFromString<List<GitHubRelease>>(body).mapNotNull(::toRelease)

    fun toRelease(r: GitHubRelease): Release? {
        if (r.draft) return null
        val version = AppVersion.parse(r.tag) ?: return null
        return Release(
            version = version,
            tag = r.tag,
            notes = r.body.orEmpty().trim(),
            pageUrl = r.htmlUrl,
            publishedAt = r.publishedAt,
            phoneApk = r.assets.firstOrNull { it.name.startsWith(PHONE_PREFIX) && it.name.endsWith(".apk") },
            watchApk = r.assets.firstOrNull { it.name.startsWith(WATCH_PREFIX) && it.name.endsWith(".apk") },
            checksums = r.assets.firstOrNull { it.name == CHECKSUMS }
        )
    }

    /** Which releases a track receives: stable → stable; beta → beta + stable; dev → everything. */
    fun receives(track: AppVersion.Channel, release: AppVersion.Channel): Boolean = release.ordinal <= track.ordinal

    private fun instant(release: Release): Instant? = release.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** The version without its -beta/-dev suffix, to compare bases (0.0.2.102-dev.5 → 0.0.2.102). */
    private fun base(v: AppVersion) = v.copy(preRelease = emptyList())

    private fun candidates(releases: List<Release>, track: AppVersion.Channel) =
        releases.filter { it.phoneApk != null && receives(track, it.version.channel) }

    /**
     * The newest release of [track] (for comparing the watch's version). Dev builds and betas of one
     * version interleave, so on the dev track "newest" means most recently published.
     */
    fun newest(releases: List<Release>, track: AppVersion.Channel): Release? {
        val offered = candidates(releases, track)
        return if (track == AppVersion.Channel.DEV) {
            offered.maxWithOrNull(compareBy<Release>({ instant(it) ?: Instant.EPOCH }, { it.version }))
        } else {
            offered.maxByOrNull { it.version }
        }
    }

    /**
     * The release to offer on top of [current], or null when there's nothing newer for [track]:
     * - stable: the next stable release;
     * - beta: the next beta or stable release;
     * - after switching to stable or beta from a build of another channel (a dev build on the beta
     *   track, a beta on the stable track): also any release of the track published after the
     *   installed build, such as 0.0.2.108-beta.1 after 0.0.2.108-dev.9. Published later means a
     *   higher versionCode, so it installs over it;
     * - dev: anything published after the installed build (next dev, beta or stable), as long as
     *   it isn't for an older version. A build that isn't a published release (a local build)
     *   falls back to version order, with dev builds before the betas of their version.
     */
    fun update(releases: List<Release>, current: String, track: AppVersion.Channel): Release? {
        val installed = AppVersion.parse(current) ?: return null
        val offered = candidates(releases, track)
        val installedAt = releases.firstOrNull { it.version == installed }?.let(::instant)
        if (track != AppVersion.Channel.DEV) {
            val left = installedAt.takeIf { !receives(track, installed.channel) }
            return offered.filter {
                it.version > installed || (left != null && instant(it)?.isAfter(left) == true)
            }.maxByOrNull { it.version }
        }
        val newer = if (installedAt != null) {
            offered.filter { r -> instant(r)?.isAfter(installedAt) == true && base(r.version) >= base(installed) && r.version != installed }
        } else {
            val asOrdered = if (installed.isDev) installed.copy(preRelease = listOf("0")) else installed
            offered.filter { it.version > asOrdered }
        }
        return newer.maxWithOrNull(compareBy<Release>({ instant(it) ?: Instant.EPOCH }, { it.version }))
    }

    /**
     * True when the watch should install [latest]: it runs another version that was published
     * before [latest] (found in [releases]). Every build's versionCode counts up with time, so a
     * later release always installs over an earlier one, across channels: a watch on
     * 0.0.2.108-dev.3 is behind a 0.0.2.108-beta.2 published after it. A watch build that isn't a
     * release (a local build) falls back to version order.
     */
    fun watchBehind(watchVersion: String?, latest: Release?, releases: List<Release> = emptyList()): Boolean {
        val watch = watchVersion?.let(AppVersion::parse) ?: return false
        if (latest?.watchApk == null || latest.version == watch) return false
        val watchAt = releases.firstOrNull { it.version == watch }?.let(::instant)
        val latestAt = instant(latest)
        return if (watchAt != null && latestAt != null) latestAt.isAfter(watchAt) else latest.version > watch
    }

    /** Parses `sha256sum` output: "<hex>  <file>" (or "<hex> *<file>") per line → file name to lowercase hash. */
    fun parseChecksums(text: String): Map<String, String> = text.lines()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) parts[1].removePrefix("*") to parts[0].lowercase() else null
        }
        .toMap()
}
