// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.legal

import android.content.Context
import androidx.annotation.StringRes
import com.heartline.phone.R

/** Documents bundled from the repository into assets/docs/ (see bundleDocs in build.gradle.kts). */
enum class BundledDoc(val file: String, @StringRes val title: Int, val repoDir: String) {
    TERMS("TERMS_OF_USE.md", R.string.legal_terms, "legal"),
    PRIVACY("PRIVACY_POLICY.md", R.string.legal_privacy, "legal"),
    DISCLAIMER("MEDICAL_DISCLAIMER.md", R.string.settings_disclaimer, "legal"),
    CHANGELOG("CHANGELOG.md", R.string.whats_new_title, ""),
    ;

    val route: String get() = name.lowercase()

    fun read(context: Context): String = runCatching { context.assets.open("docs/$file").bufferedReader().use { it.readText() } }.getOrDefault("")

    companion object {
        fun fromRoute(route: String?) = entries.firstOrNull { it.route == route }

        /** A link inside a bundled document that points at another bundled document. */
        fun fromLink(href: String) = entries.firstOrNull { href.substringBefore('#').endsWith(it.file) }
    }
}
