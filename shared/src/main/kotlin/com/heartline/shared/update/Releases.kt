// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.update

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

    /**
     * The newest release to offer on top of [current]: stable releases always, betas only when
     * [includeBeta], development builds never. Null when [current] is already the newest.
     */
    fun newest(releases: List<Release>, includeBeta: Boolean): Release? = releases
        .filter { it.phoneApk != null && it.version.channel != AppVersion.Channel.DEV }
        .filter { includeBeta || it.version.channel == AppVersion.Channel.STABLE }
        .maxByOrNull { it.version }

    fun update(releases: List<Release>, current: String, includeBeta: Boolean): Release? {
        val parsed = AppVersion.parse(current) ?: return null
        // A development build comes before every beta of its version ("0" sorts below "beta").
        val installed = if (parsed.isDev) parsed.copy(preRelease = listOf("0")) else parsed
        return newest(releases, includeBeta)?.takeIf { it.version > installed }
    }

    /** True when the watch runs an older version than [latest] (or its version is unknown but set). */
    fun watchBehind(watchVersion: String?, latest: Release?): Boolean {
        val watch = watchVersion?.let(AppVersion::parse) ?: return false
        return latest?.watchApk != null && latest.version > watch
    }

    /** Parses `sha256sum` output: "<hex>  <file>" (or "<hex> *<file>") per line → file name to lowercase hash. */
    fun parseChecksums(text: String): Map<String, String> = text.lines()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) parts[1].removePrefix("*") to parts[0].lowercase() else null
        }
        .toMap()
}
