// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.update

import kotlin.math.sign

/**
 * A semantic version as used by release tags: `1.2`, `1.2.0`, `v1.2.0-beta.3`, `1.2.0-dev.14`,
 * optionally with a fourth number: `0.0.2.102`, `0.0.2.102-dev.57`.
 * Build metadata (`+…`) is ignored. Ordering follows SemVer 2.0: 1.2.0-beta.2 < 1.2.0-beta.10 < 1.2.0.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
    /** The optional fourth number; null when the version has three. */
    val build: Int? = null
) : Comparable<AppVersion> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    /** Development builds (`-dev.N`) are never offered as updates. */
    val isDev: Boolean get() = preRelease.firstOrNull()?.lowercase() == "dev"

    val channel: Channel get() = when {
        isDev -> Channel.DEV
        isPreRelease -> Channel.BETA
        else -> Channel.STABLE
    }

    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        compareValues(build ?: 0, other.build ?: 0).let { if (it != 0) return it }
        // A release sorts after its pre-releases.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) return other.preRelease.size.sign - preRelease.size.sign
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString() =
        "$major.$minor.$patch" + (build?.let { ".$it" } ?: "") + if (isPreRelease) "-" + preRelease.joinToString(".") else ""

    enum class Channel { STABLE, BETA, DEV }

    companion object {
        private val pattern = Regex("^[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(text: String): AppVersion? {
            val m = pattern.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, build, pre) = m.destructured
            return AppVersion(
                major.toInt(),
                minor.toInt(),
                patch.ifEmpty { "0" }.toInt(),
                pre.split('.').filter { it.isNotEmpty() },
                build.toIntOrNull()
            )
        }
    }
}
