// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.diag

/** Replaces the user's names and birth date in log text, so exported logs don't identify them. */
class Redactor(names: Collection<String> = emptyList(), private val birthDate: String? = null) {
    // Longest first, so "Sara Smith" is replaced before "Sara"; very short names would hit ordinary words.
    private val names = names.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }
    private val patterns = this.names.map { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) }

    fun redact(text: String): String {
        var out = text
        for (p in patterns) out = p.replace(out, NAME)
        if (!birthDate.isNullOrBlank()) out = out.replace(birthDate, BIRTH_DATE)
        return out
    }

    companion object {
        const val NAME = "<name>"
        const val BIRTH_DATE = "<birthdate>"
        val NONE = Redactor()

        /** Hides every name and the birth date in [profile]. */
        fun of(profile: com.heartline.shared.profile.UserProfile?): Redactor = if (profile == null) {
            NONE
        } else {
            Redactor(
                listOf(profile.firstName, profile.lastName, profile.preferredName, "${profile.firstName} ${profile.lastName}"),
                profile.birthDate
            )
        }
    }
}
