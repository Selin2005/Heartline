package com.heartline.shared.profile

import java.time.LocalDate

enum class ProfileField { FIRST_NAME, LAST_NAME, BIRTH_DATE, GENDER, HEIGHT, WEIGHT }

/** One precise problem with one field, so the form can say exactly what is wrong and where. */
enum class ProfileError(val field: ProfileField) {
    FIRST_NAME_MISSING(ProfileField.FIRST_NAME),
    LAST_NAME_MISSING(ProfileField.LAST_NAME),
    BIRTH_DATE_MISSING(ProfileField.BIRTH_DATE),
    BIRTH_DATE_FUTURE(ProfileField.BIRTH_DATE),
    TOO_YOUNG(ProfileField.BIRTH_DATE),
    TOO_OLD(ProfileField.BIRTH_DATE),
    GENDER_MISSING(ProfileField.GENDER),
    HEIGHT_MISSING(ProfileField.HEIGHT),
    HEIGHT_OUT_OF_RANGE(ProfileField.HEIGHT),
    WEIGHT_MISSING(ProfileField.WEIGHT),
    WEIGHT_OUT_OF_RANGE(ProfileField.WEIGHT)
}

object ProfileValidator {
    /** Every problem, at most one per field, in form order. */
    fun errors(profile: UserProfile, today: LocalDate = LocalDate.now()): Map<ProfileField, ProfileError> {
        val out = linkedMapOf<ProfileField, ProfileError>()
        fun add(e: ProfileError) = out.putIfAbsent(e.field, e)
        if (profile.firstName.isBlank()) add(ProfileError.FIRST_NAME_MISSING)
        if (profile.lastName.isBlank()) add(ProfileError.LAST_NAME_MISSING)
        val born = profile.birthLocalDate
        when {
            born == null -> add(ProfileError.BIRTH_DATE_MISSING)
            born.isAfter(today) -> add(ProfileError.BIRTH_DATE_FUTURE)
            else -> {
                val age = profile.age(today) ?: 0
                if (age < UserProfile.MIN_AGE) add(ProfileError.TOO_YOUNG)
                if (age > UserProfile.MAX_AGE) add(ProfileError.TOO_OLD)
            }
        }
        if (profile.gender == null) add(ProfileError.GENDER_MISSING)
        when {
            profile.heightCm <= 0f -> add(ProfileError.HEIGHT_MISSING)
            profile.heightCm !in UserProfile.HEIGHT_CM -> add(ProfileError.HEIGHT_OUT_OF_RANGE)
        }
        when {
            profile.weightKg <= 0f -> add(ProfileError.WEIGHT_MISSING)
            profile.weightKg !in UserProfile.WEIGHT_KG -> add(ProfileError.WEIGHT_OUT_OF_RANGE)
        }
        return out
    }
}

/** Lenient number parsing for typed values: Persian/Arabic digits and ',' / '٫' decimal separators. */
object NumberInput {
    fun normalize(raw: String): String = buildString {
        for (c in raw.trim()) {
            when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                '٫', ',', '،' -> append('.')
                else -> if (c.isDigit() || c == '.') append(c)
            }
        }
    }

    fun parse(raw: String): Float? = normalize(raw).takeIf { it.isNotEmpty() && it.count { c -> c == '.' } <= 1 }?.toFloatOrNull()

    fun cmFromFeet(feet: Float, inches: Float) = (feet * 12f + inches) * 2.54f

    fun kgFromPounds(lb: Float) = lb * 0.45359237f

    fun poundsFromKg(kg: Float) = kg / 0.45359237f
}
