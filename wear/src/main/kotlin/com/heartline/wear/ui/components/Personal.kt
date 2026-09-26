package com.heartline.wear.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.heartline.shared.profile.Ago
import com.heartline.shared.profile.DayPart
import com.heartline.wear.R
import com.heartline.wear.ui.theme.WearColors
import java.time.Instant
import java.time.ZoneId

/** The name the watch greets the user with (null when unknown or hidden in settings). */
val LocalUserName = compositionLocalOf<String?> { null }

/** "Good morning, Sara", or "Happy birthday, Sara!" on the day. */
@Composable
fun greeting(part: DayPart, name: String?, birthday: Boolean = false): String = when {
    birthday && name != null -> stringResource(R.string.greeting_birthday_named, name)
    birthday -> stringResource(R.string.greeting_birthday)
    else -> {
        val text = stringResource(
            when (part) {
                DayPart.MORNING -> R.string.greeting_morning
                DayPart.AFTERNOON -> R.string.greeting_afternoon
                DayPart.EVENING -> R.string.greeting_evening
                DayPart.NIGHT -> R.string.greeting_night
            },
        )
        if (name == null) text else stringResource(R.string.greeting_named, text, name)
    }
}

/** "2 h ago", "Yesterday"… for a reading taken at [atMs]. */
@Composable
fun agoText(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    return when (val ago = Ago.of(atMs, nowMs) { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }) {
        Ago.JustNow -> stringResource(R.string.ago_now)
        is Ago.Minutes -> stringResource(R.string.ago_minutes, ago.n)
        is Ago.Hours -> stringResource(R.string.ago_hours, ago.n)
        Ago.Yesterday -> stringResource(R.string.ago_yesterday)
        is Ago.Days -> stringResource(R.string.ago_days, ago.n)
    }
}

/** Which kind of good result a [PersonalNote] cheers. */
enum class GoodResult(val notes: List<Int>) {
    ECG(listOf(R.string.note_ecg_1, R.string.note_ecg_2, R.string.note_ecg_3)),
    BLOOD_PRESSURE(listOf(R.string.note_bp_1, R.string.note_bp_2, R.string.note_bp_3)),
    SPO2(listOf(R.string.note_spo2_1, R.string.note_spo2_2)),
    CALM(listOf(R.string.note_calm_1, R.string.note_calm_2)),
    DONE(listOf(R.string.note_done_1, R.string.note_done_2)),
}

/**
 * A short, friendly line with the user's name under a normal result ("Looking good, Sara"). Only
 * for normal results, and only when the name is shown: abnormal results keep a plain medical tone.
 */
@Composable
fun PersonalNote(kind: GoodResult, modifier: Modifier = Modifier) {
    val name = LocalUserName.current ?: return
    val note = remember(kind) { kind.notes.random() }
    Text(
        stringResource(note, name),
        style = MaterialTheme.typography.bodySmall,
        color = WearColors.primary,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
    )
}
