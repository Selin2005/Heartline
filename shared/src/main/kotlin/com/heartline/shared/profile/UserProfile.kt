package com.heartline.shared.profile

import java.time.LocalDate
import java.time.Period
import kotlinx.serialization.Serializable

/** Sex used only for physiological calculations (body composition, reference ranges). */
@Serializable
enum class Sex { FEMALE, MALE }

/** How the user describes their gender. Independent of [Sex]. */
@Serializable
enum class Gender {
    WOMAN,
    MAN,
    NON_BINARY,
    TRANS_WOMAN,
    TRANS_MAN,
    GENDERQUEER,
    AGENDER,
    TWO_SPIRIT,
    SELF_DESCRIBE,
    PREFER_NOT_TO_SAY;

    /** Woman and man imply the calculation sex; every other answer asks for it separately (optional). */
    val impliedSex: Sex? get() = when (this) {
        WOMAN -> Sex.FEMALE
        MAN -> Sex.MALE
        else -> null
    }

    /** The name-on-reports choice is offered to people outside the woman/man binary. */
    val offersReportNameChoice: Boolean get() = impliedSex == null
}

/** Which name is printed on exports (PDF, CSV, shares). */
@Serializable
enum class ReportName { FULL_NAME, PREFERRED_NAME }

/**
 * The user's profile; edited on the phone and synced to the watch.
 * [birthYear] is only read from profiles saved by older versions.
 */
@Serializable
data class UserProfile(
    val firstName: String = "",
    val lastName: String = "",
    val preferredName: String = "",
    val birthDate: String? = null,
    val gender: Gender? = null,
    val genderDescription: String = "",
    val sex: Sex? = null,
    val heightCm: Float = 0f,
    val weightKg: Float = 0f,
    val reportName: ReportName = ReportName.FULL_NAME,
    val birthYear: Int? = null
) {
    val birthLocalDate: LocalDate? get() = birthDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Exact age in whole years; falls back to the legacy birth year. */
    fun age(today: LocalDate = LocalDate.now()): Int? {
        val born = birthLocalDate
        return when {
            born != null -> Period.between(born, today).years
            birthYear != null -> today.year - birthYear
            else -> null
        }
    }

    /** The name the app greets the user with. */
    val displayName: String get() = preferredName.trim().ifEmpty { firstName.trim() }

    val fullName: String get() = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** The name printed on reports and exports. */
    val reportDisplayName: String get() =
        if (reportName == ReportName.PREFERRED_NAME && gender?.offersReportNameChoice == true) displayName else fullName

    /** The sex used by calculations: the one implied by the gender, else the one the user chose (may be null). */
    val calcSex: Sex? get() = gender?.impliedSex ?: sex

    val isComplete: Boolean get() = ProfileValidator.errors(this).isEmpty()

    companion object {
        const val MIN_AGE = 13
        const val MAX_AGE = 120
        val HEIGHT_CM = 100f..250f
        val WEIGHT_KG = 25f..300f
    }
}
