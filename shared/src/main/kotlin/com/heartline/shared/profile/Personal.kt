package com.heartline.shared.profile

/** The part of the day a greeting is for. */
enum class DayPart {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT;

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** How long ago something was measured, for "2 h ago"-style labels. */
sealed interface Ago {
    data object JustNow : Ago

    data class Minutes(val n: Int) : Ago

    data class Hours(val n: Int) : Ago

    data object Yesterday : Ago

    data class Days(val n: Int) : Ago

    companion object {
        /** [dayOf]: the local calendar day of a time, so "yesterday" follows midnight, not 24 hours. */
        fun of(atMs: Long, nowMs: Long, dayOf: (Long) -> Long): Ago {
            val minutes = ((nowMs - atMs) / 60_000L).coerceAtLeast(0)
            val days = (dayOf(nowMs) - dayOf(atMs)).toInt()
            return when {
                minutes < 1 -> JustNow
                minutes < 60 -> Minutes(minutes.toInt())
                days <= 0 -> Hours((minutes / 60).toInt())
                days == 1 -> Yesterday
                else -> Days(days)
            }
        }
    }
}
