// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.shared.body

import com.heartline.shared.model.RecordSummary
import com.heartline.shared.profile.Sex
import kotlin.math.abs

/** Where a value falls against its reference range. */
enum class BodyLevel { LOW, STANDARD, HIGH, VERY_HIGH }

/**
 * A reference range drawn as a bar: [min]..[max] is the whole bar, [low]..[high] the standard band,
 * [veryHigh] (if any) starts the last band.
 */
data class BodyRange(val min: Float, val low: Float, val high: Float, val max: Float, val veryHigh: Float? = null) {
    fun level(v: Float): BodyLevel = when {
        v < low -> BodyLevel.LOW
        veryHigh != null && v >= veryHigh -> BodyLevel.VERY_HIGH
        v > high -> BodyLevel.HIGH
        else -> BodyLevel.STANDARD
    }

    /** Position of [v] along the bar, 0–1. */
    fun position(v: Float): Float = ((v - min) / (max - min)).coerceIn(0f, 1f)
}

/**
 * Body type from body fat and muscle levels (InBody-style 3 × 3 grid): e.g. high fat with low
 * muscle is "hidden overweight" even at a normal weight.
 */
enum class BodyType(val fat: BodyLevel, val muscle: BodyLevel) {
    SLIM(BodyLevel.LOW, BodyLevel.LOW),
    LEAN(BodyLevel.LOW, BodyLevel.STANDARD),
    ATHLETIC(BodyLevel.LOW, BodyLevel.HIGH),
    UNDER_EXERCISED(BodyLevel.STANDARD, BodyLevel.LOW),
    BALANCED(BodyLevel.STANDARD, BodyLevel.STANDARD),
    MUSCULAR(BodyLevel.STANDARD, BodyLevel.HIGH),
    HIDDEN_OVERWEIGHT(BodyLevel.HIGH, BodyLevel.LOW),
    OVERFAT(BodyLevel.HIGH, BodyLevel.STANDARD),
    SOLIDLY_BUILT(BodyLevel.HIGH, BodyLevel.HIGH);

    /** Grid position: column = muscle (0 low … 2 high), row = fat (0 low … 2 high). */
    val column: Int get() = muscle.grid
    val row: Int get() = fat.grid

    companion object {
        fun of(fat: BodyLevel, muscle: BodyLevel): BodyType = entries.first { it.fat.grid == fat.grid && it.muscle.grid == muscle.grid }
    }
}

private val BodyLevel.grid: Int get() = when (this) {
    BodyLevel.LOW -> 0
    BodyLevel.STANDARD -> 1
    BodyLevel.HIGH, BodyLevel.VERY_HIGH -> 2
}

/** One body composition measurement with everything derived from it (the watch and phone screens show this). */
data class BodyReport(
    val weightKg: Float?,
    val heightCm: Float?,
    val bodyFatPercent: Float,
    val fatMassKg: Float?,
    val skeletalMuscleKg: Float?,
    val skeletalMusclePercent: Float?,
    val bodyWaterKg: Float?,
    val bodyWaterPercent: Float?,
    val fatFreeMassKg: Float?,
    val bmi: Float?,
    val bmrKcal: Int?,
    val impedanceOhm: Float?,
    val phaseAngleDeg: Float?,
    val ranges: BodyRanges
) {
    val fatLevel: BodyLevel get() = ranges.bodyFat.level(bodyFatPercent)
    val muscleLevel: BodyLevel? get() = skeletalMusclePercent?.let { ranges.muscle.level(it) }
    val bodyType: BodyType? get() = muscleLevel?.let { BodyType.of(fatLevel, it) }

    /** Shares of body weight for the composition ring: fat, muscle, water outside muscle is not separable, so "other" is the rest. */
    val shares: List<Float>? get() {
        val w = weightKg?.takeIf { it > 0 } ?: return null
        val fat = (fatMassKg ?: return null) / w
        val muscle = (skeletalMuscleKg ?: return null) / w
        return listOf(fat, muscle, (1f - fat - muscle).coerceAtLeast(0f))
    }
}

data class BodyRanges(val bodyFat: BodyRange, val bmi: BodyRange, val muscle: BodyRange, val water: BodyRange, val phaseAngle: BodyRange)

object BodyComposition {
    /**
     * Reference ranges by sex and age:
     * - body fat %: Gallagher et al. 2000 (Am J Clin Nutr 72:694), healthy band by age;
     * - BMI: WHO;
     * - skeletal muscle % of weight: typical BIA reference bands (InBody-style standard);
     * - body water % of weight: 50–65 % (men), 45–60 % (women);
     * - phase angle (50 kHz): Barbosa-Silva et al. 2005 (Am J Clin Nutr 82:49), mean ± 1 SD by age.
     */
    fun ranges(sex: Sex?, age: Int?): BodyRanges {
        val a = age ?: 40
        val male = sex == Sex.MALE
        val fat = when {
            male && a < 40 -> BodyRange(0f, 8f, 20f, 40f, 25f)
            male && a < 60 -> BodyRange(0f, 11f, 22f, 40f, 28f)
            male -> BodyRange(0f, 13f, 25f, 40f, 30f)
            a < 40 -> BodyRange(5f, 21f, 33f, 50f, 39f)
            a < 60 -> BodyRange(5f, 23f, 34f, 50f, 40f)
            else -> BodyRange(5f, 24f, 36f, 50f, 42f)
        }
        val muscle = if (male) BodyRange(25f, 38f, 48f, 60f) else BodyRange(20f, 30f, 38f, 50f)
        val water = if (male) BodyRange(35f, 50f, 65f, 75f) else BodyRange(30f, 45f, 60f, 70f)
        val phase = when {
            male && a < 40 -> BodyRange(3f, 6.2f, 8.0f, 10f)
            male && a < 60 -> BodyRange(3f, 5.8f, 7.6f, 10f)
            male -> BodyRange(3f, 4.9f, 6.7f, 10f)
            a < 40 -> BodyRange(3f, 5.3f, 6.9f, 10f)
            a < 60 -> BodyRange(3f, 5.2f, 6.8f, 10f)
            else -> BodyRange(3f, 4.5f, 6.0f, 10f)
        }
        return BodyRanges(fat, BodyRange(14f, 18.5f, 24.9f, 40f, 30f), muscle, water, phase)
    }

    fun bmi(weightKg: Float?, heightCm: Float?): Float? {
        val w = weightKg?.takeIf { it > 0 } ?: return null
        val h = heightCm?.takeIf { it > 0 }?.div(100f) ?: return null
        return w / (h * h)
    }

    /** Everything shown for [s]; [fallbackWeightKg]/[fallbackHeightCm] fill in records made before they were stored. */
    fun report(
        s: RecordSummary.BodyComposition,
        sex: Sex?,
        age: Int?,
        fallbackWeightKg: Float? = null,
        fallbackHeightCm: Float? = null
    ): BodyReport {
        val weight = s.weightKg ?: fallbackWeightKg?.takeIf { it > 0 }
        val height = s.heightCm ?: fallbackHeightCm?.takeIf { it > 0 }
        val fatMass = s.bodyFatMassKg ?: weight?.let { it * s.bodyFatPercent / 100f }
        return BodyReport(
            weightKg = weight,
            heightCm = height,
            bodyFatPercent = s.bodyFatPercent,
            fatMassKg = fatMass,
            skeletalMuscleKg = s.skeletalMuscleKg,
            skeletalMusclePercent = s.skeletalMusclePercent ?: s.skeletalMuscleKg?.let { m -> weight?.let { 100f * m / it } },
            bodyWaterKg = s.bodyWaterKg,
            bodyWaterPercent = s.bodyWaterKg?.let { water -> weight?.let { 100f * water / it } },
            fatFreeMassKg = s.fatFreeMassKg ?: fatMass?.let { f -> weight?.let { it - f } },
            bmi = bmi(weight, height),
            bmrKcal = s.bmrKcal,
            impedanceOhm = s.impedanceOhm,
            // The SDK reports the impedance angle as −90…0 degrees; the phase angle is its magnitude.
            phaseAngleDeg = s.phaseAngleDeg?.let { abs(it) }?.takeIf { it > 0f },
            ranges = ranges(sex, age)
        )
    }
}
