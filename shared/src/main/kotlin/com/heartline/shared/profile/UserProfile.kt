package com.heartline.shared.profile

import kotlinx.serialization.Serializable

@Serializable
enum class Sex { FEMALE, MALE }

/** Needed by body composition (BIA); edited on the phone and synced to the watch. */
@Serializable
data class UserProfile(val birthYear: Int, val sex: Sex, val heightCm: Float, val weightKg: Float) {
    fun age(currentYear: Int) = (currentYear - birthYear).coerceIn(10, 110)

    val isComplete get() = birthYear in 1900..2100 && heightCm in 100f..250f && weightKg in 25f..300f
}
