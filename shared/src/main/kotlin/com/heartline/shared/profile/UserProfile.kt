package com.heartline.shared.profile

import java.time.LocalDate
import java.time.Period
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Sex used only for physiological calculations (body composition, reference ranges). */
@Serializable
enum class Sex { FEMALE, MALE }

/** How the user describes their gender. Independent of [Sex]. */
@Serializable(with = GenderSerializer::class)
enum class Gender {
    WOMAN,
    MAN,
    PREFER_NOT_TO_SAY;

    /** Woman and man imply the calculation sex; "prefer not to say" asks for it separately (optional). */
    val impliedSex: Sex? get() = when (this) {
        WOMAN -> Sex.FEMALE
        MAN -> Sex.MALE
        PREFER_NOT_TO_SAY -> null
    }
}

/** Reads profiles saved with the earlier, longer gender list: anything else becomes "prefer not to say". */
object GenderSerializer : KSerializer<Gender> {
    override val descriptor = PrimitiveSerialDescriptor("Gender", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Gender) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): Gender {
        val name = decoder.decodeString()
        return Gender.entries.firstOrNull { it.name == name } ?: Gender.PREFER_NOT_TO_SAY
    }
}

/** Which name is printed on exports (PDF, image, shares). A phone setting; the nickname by default. */
@Serializable
enum class ReportName { PREFERRED_NAME, FULL_NAME, NONE }

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
    val sex: Sex? = null,
    val heightCm: Float = 0f,
    val weightKg: Float = 0f,
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

    /** The name printed on reports and exports for the user's choice; null for "no name". */
    fun reportName(choice: ReportName): String? = when (choice) {
        ReportName.PREFERRED_NAME -> displayName
        ReportName.FULL_NAME -> fullName
        ReportName.NONE -> null
    }?.takeIf { it.isNotBlank() }

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
