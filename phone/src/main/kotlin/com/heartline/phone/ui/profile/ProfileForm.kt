package com.heartline.phone.ui.profile

import com.heartline.shared.profile.Gender
import com.heartline.shared.profile.NumberInput
import com.heartline.shared.profile.ProfileError
import com.heartline.shared.profile.ProfileField
import com.heartline.shared.profile.ProfileValidator
import com.heartline.shared.profile.ReportName
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import java.time.LocalDate
import kotlin.math.roundToInt

enum class HeightUnit { CM, FT_IN }

enum class WeightUnit { KG, LB }

/** Raw, as-typed form values. Turned into a [UserProfile] plus per-field errors by [evaluate]. */
data class ProfileForm(
    val firstName: String = "",
    val lastName: String = "",
    val preferredName: String = "",
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    val genderDescription: String = "",
    val sex: Sex? = null,
    val heightUnit: HeightUnit = HeightUnit.CM,
    val heightCm: String = "",
    val heightFt: String = "",
    val heightIn: String = "",
    val weightUnit: WeightUnit = WeightUnit.KG,
    val weight: String = "",
    val reportName: ReportName = ReportName.FULL_NAME
) {
    fun toProfile(): UserProfile = UserProfile(
        firstName = firstName.trim(),
        lastName = lastName.trim(),
        preferredName = preferredName.trim(),
        birthDate = birthDate?.toString(),
        gender = gender,
        genderDescription = if (gender == Gender.SELF_DESCRIBE) genderDescription.trim() else "",
        sex = if (gender?.impliedSex != null) gender.impliedSex else sex,
        heightCm = heightInCm() ?: 0f,
        weightKg = weightInKg() ?: 0f,
        reportName = if (gender?.offersReportNameChoice == true) reportName else ReportName.FULL_NAME
    )

    fun evaluate(today: LocalDate = LocalDate.now()): Map<ProfileField, ProfileError> = ProfileValidator.errors(toProfile(), today)

    private fun heightInCm(): Float? = when (heightUnit) {
        HeightUnit.CM -> NumberInput.parse(heightCm)
        HeightUnit.FT_IN -> NumberInput.parse(heightFt)?.let { ft -> NumberInput.cmFromFeet(ft, NumberInput.parse(heightIn) ?: 0f) }
    }

    private fun weightInKg(): Float? = NumberInput.parse(weight)?.let { if (weightUnit == WeightUnit.LB) NumberInput.kgFromPounds(it) else it }

    /** Switches unit while keeping the entered value. */
    fun withHeightUnit(unit: HeightUnit): ProfileForm {
        if (unit == heightUnit) return this
        val cm = heightInCm()
        return if (unit == HeightUnit.FT_IN) {
            val totalIn = cm?.let { (it / 2.54f).roundToInt() }
            copy(heightUnit = unit, heightFt = totalIn?.let { (it / 12).toString() }.orEmpty(), heightIn = totalIn?.let { (it % 12).toString() }.orEmpty())
        } else {
            copy(heightUnit = unit, heightCm = cm?.roundToInt()?.toString().orEmpty())
        }
    }

    fun withWeightUnit(unit: WeightUnit): ProfileForm {
        if (unit == weightUnit) return this
        val kg = weightInKg()
        val shown = kg?.let { if (unit == WeightUnit.LB) NumberInput.poundsFromKg(it) else it }
        return copy(weightUnit = unit, weight = shown?.let { format1(it) }.orEmpty())
    }

    companion object {
        fun from(profile: UserProfile?): ProfileForm {
            if (profile == null) return ProfileForm()
            return ProfileForm(
                firstName = profile.firstName,
                lastName = profile.lastName,
                preferredName = profile.preferredName,
                birthDate = profile.birthLocalDate,
                gender = profile.gender,
                genderDescription = profile.genderDescription,
                sex = profile.sex,
                heightCm = profile.heightCm.takeIf { it > 0f }?.roundToInt()?.toString().orEmpty(),
                weight = profile.weightKg.takeIf { it > 0f }?.let { format1(it) }.orEmpty(),
                reportName = profile.reportName
            )
        }

        private fun format1(v: Float): String {
            val r = (v * 10f).roundToInt() / 10f
            return if (r % 1f == 0f) r.toInt().toString() else r.toString()
        }
    }
}
