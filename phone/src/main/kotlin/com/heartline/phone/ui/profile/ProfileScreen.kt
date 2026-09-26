// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.profile

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.profile.Gender
import com.heartline.shared.profile.ProfileError
import com.heartline.shared.profile.ProfileField
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The profile form. Every field shows its own error right under it, once touched or after a save attempt,
 * and a failed save scrolls to the first field that needs attention.
 */
@Composable
fun ProfileScreen(
    profile: UserProfile?,
    onBack: (() -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
    initialShowErrors: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    onSave: (UserProfile) -> Unit = {}
) {
    val colors = HeartlineTheme.colors
    var form by remember(profile) { mutableStateOf(ProfileForm.from(profile)) }
    var touched by rememberSaveable { mutableStateOf(setOf<ProfileField>()) }
    var showAll by rememberSaveable { mutableStateOf(initialShowErrors) }
    val errors = form.evaluate(today)
    fun error(field: ProfileField) = errors[field]?.takeIf { showAll || field in touched }
    fun touch(field: ProfileField) {
        touched = touched + field
    }
    val scope = rememberCoroutineScope()

    ReachabilityScaffold(title = stringResource(R.string.settings_profile), onBack = onBack, listState = listState) {
        item(key = "names") {
            RoundedCard(Modifier.gutter()) {
                SectionTitle(stringResource(R.string.profile_section_you))
                Text(stringResource(R.string.profile_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                FormField(
                    stringResource(R.string.profile_first_name),
                    form.firstName,
                    { form = form.copy(firstName = it.take(40)) },
                    error(ProfileField.FIRST_NAME),
                    onBlur = { touch(ProfileField.FIRST_NAME) },
                    keyboard = nameKeyboard
                )
                FormField(
                    stringResource(R.string.profile_last_name),
                    form.lastName,
                    { form = form.copy(lastName = it.take(40)) },
                    error(ProfileField.LAST_NAME),
                    onBlur = { touch(ProfileField.LAST_NAME) },
                    keyboard = nameKeyboard
                )
                FormField(
                    stringResource(R.string.profile_preferred_name),
                    form.preferredName,
                    { form = form.copy(preferredName = it.take(40)) },
                    null,
                    helper = stringResource(R.string.profile_preferred_name_help),
                    keyboard = nameKeyboard
                )
            }
        }
        item(key = "birth") {
            RoundedCard(Modifier.gutter()) {
                SectionTitle(stringResource(R.string.profile_birth_date))
                BirthDateField(form.birthDate, today, error(ProfileField.BIRTH_DATE)) {
                    form = form.copy(birthDate = it)
                    touch(ProfileField.BIRTH_DATE)
                }
            }
        }
        item(key = "gender") {
            RoundedCard(Modifier.gutter()) {
                GenderSection(form, error(ProfileField.GENDER)) {
                    form = it
                    touch(ProfileField.GENDER)
                }
            }
        }
        item(key = "body") {
            RoundedCard(Modifier.gutter()) {
                SectionTitle(stringResource(R.string.profile_section_body))
                BodySection(form, error(ProfileField.HEIGHT), error(ProfileField.WEIGHT), { form = it }, ::touch)
            }
        }
        item(key = "save") {
            Column(Modifier.gutter()) {
                if (showAll && errors.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.profile_fix_fields, errors.size, errors.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.statusAlert,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                PillButton(
                    stringResource(R.string.action_save),
                    onClick = {
                        if (errors.isEmpty()) {
                            onSave(form.toProfile())
                        } else {
                            showAll = true
                            val first = errors.keys.first()
                            scope.launch { listState.animateScrollToItem(sectionIndex(first)) }
                        }
                    }
                )
            }
        }
    }
}

/** LazyColumn index of the card holding [field] (index 0 is the scaffold header). */
private fun sectionIndex(field: ProfileField) = when (field) {
    ProfileField.FIRST_NAME, ProfileField.LAST_NAME -> 1
    ProfileField.BIRTH_DATE -> 2
    ProfileField.GENDER -> 3
    ProfileField.HEIGHT, ProfileField.WEIGHT -> 4
}

private val nameKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = HeartlineTheme.colors.onBackground)
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateField(value: LocalDate?, today: LocalDate, error: ProfileError?, onPick: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val formatted = value?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)).orEmpty()
    val age = value?.let { java.time.Period.between(it, today).years }
    FormField(
        label = stringResource(R.string.profile_birth_date),
        value = formatted,
        onChange = {},
        error = error,
        helper = if (age != null && age >= 0) pluralStringResource(R.plurals.profile_age, age, age) else stringResource(R.string.profile_birth_date_help),
        readOnly = true,
        onClick = { open = true },
        trailing = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) }
    )
    if (open) {
        val todayMs = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            initialDisplayedMonthMillis = (value ?: today.minusYears(30)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = (today.year - UserProfile.MAX_AGE)..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
            }
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenderSection(form: ProfileForm, error: ProfileError?, onChange: (ProfileForm) -> Unit) {
    val colors = HeartlineTheme.colors
    SectionTitle(stringResource(R.string.profile_gender))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Gender.entries.forEach { g ->
            Chip(stringResource(g.label), form.gender == g) { onChange(form.copy(gender = g)) }
        }
    }
    if (error != null) ErrorText(error)
    if (form.gender == Gender.PREFER_NOT_TO_SAY) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.profile_calc_sex), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
        Text(stringResource(R.string.profile_calc_sex_help), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.profile_female), form.sex == Sex.FEMALE) { onChange(form.copy(sex = Sex.FEMALE)) }
            Chip(stringResource(R.string.profile_male), form.sex == Sex.MALE) { onChange(form.copy(sex = Sex.MALE)) }
            Chip(stringResource(R.string.profile_skip), form.sex == null) { onChange(form.copy(sex = null)) }
        }
    }
}

@Composable
private fun BodySection(
    form: ProfileForm,
    heightError: ProfileError?,
    weightError: ProfileError?,
    onChange: (ProfileForm) -> Unit,
    touch: (ProfileField) -> Unit
) {
    val number = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    UnitRow(
        stringResource(R.string.profile_height),
        listOf(stringResource(R.string.unit_cm) to HeightUnit.CM, stringResource(R.string.unit_ft_in) to HeightUnit.FT_IN),
        form.heightUnit
    ) { onChange(form.withHeightUnit(it)) }
    if (form.heightUnit == HeightUnit.CM) {
        FormField(
            stringResource(R.string.profile_height_cm),
            form.heightCm,
            { onChange(form.copy(heightCm = it.take(6))) },
            heightError,
            onBlur = { touch(ProfileField.HEIGHT) },
            keyboard = number
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(
                stringResource(R.string.profile_height_ft),
                form.heightFt,
                { onChange(form.copy(heightFt = it.take(2))) },
                heightError,
                onBlur = { touch(ProfileField.HEIGHT) },
                keyboard = number,
                modifier = Modifier.weight(1f)
            )
            FormField(
                stringResource(R.string.profile_height_in),
                form.heightIn,
                { onChange(form.copy(heightIn = it.take(4))) },
                null,
                keyboard = number,
                modifier = Modifier.weight(1f)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    UnitRow(
        stringResource(R.string.profile_weight),
        listOf(stringResource(R.string.unit_kg) to WeightUnit.KG, stringResource(R.string.unit_lb) to WeightUnit.LB),
        form.weightUnit
    ) { onChange(form.withWeightUnit(it)) }
    FormField(
        stringResource(if (form.weightUnit == WeightUnit.KG) R.string.profile_weight_kg else R.string.profile_weight_lb),
        form.weight,
        { onChange(form.copy(weight = it.take(6))) },
        weightError,
        onBlur = { touch(ProfileField.WEIGHT) },
        keyboard = number
    )
}

@Composable
private fun <T> UnitRow(title: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = HeartlineTheme.colors.onBackground, modifier = Modifier.weight(1f))
        options.forEach { (label, value) -> Chip(label, value == selected) { onSelect(value) } }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ErrorText(error: ProfileError) {
    Text(
        stringResource(error.message),
        style = MaterialTheme.typography.bodySmall,
        color = HeartlineTheme.colors.statusAlert,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: ProfileError?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    helper: String? = null,
    onBlur: () -> Unit = {},
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = HeartlineTheme.colors
    var hadFocus by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    if (onClick != null) {
        // A read-only text field swallows clicks; open the picker when it is pressed instead.
        val pressed by interaction.collectIsPressedAsState()
        LaunchedEffect(pressed) { if (pressed) onClick() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        readOnly = readOnly,
        isError = error != null,
        supportingText = when {
            error != null -> ({ Text(stringResource(error.message)) })
            helper != null -> ({ Text(helper) })
            else -> null
        },
        trailingIcon = trailing,
        keyboardOptions = keyboard,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.divider,
            errorBorderColor = colors.statusAlert,
            errorSupportingTextColor = colors.statusAlert,
            errorLabelColor = colors.statusAlert,
            unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.4f),
            focusedContainerColor = colors.surfaceVariant.copy(alpha = 0.4f),
            errorContainerColor = colors.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .padding(bottom = 6.dp)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onBlur()
            }
    )
}

private val Gender.label: Int get() = when (this) {
    Gender.WOMAN -> R.string.gender_woman
    Gender.MAN -> R.string.gender_man
    Gender.PREFER_NOT_TO_SAY -> R.string.gender_prefer_not
}

private val ProfileError.message: Int get() = when (this) {
    ProfileError.FIRST_NAME_MISSING -> R.string.profile_err_first_name
    ProfileError.LAST_NAME_MISSING -> R.string.profile_err_last_name
    ProfileError.BIRTH_DATE_MISSING -> R.string.profile_err_birth_missing
    ProfileError.BIRTH_DATE_FUTURE -> R.string.profile_err_birth_future
    ProfileError.TOO_YOUNG -> R.string.profile_err_too_young
    ProfileError.TOO_OLD -> R.string.profile_err_too_old
    ProfileError.GENDER_MISSING -> R.string.profile_err_gender
    ProfileError.HEIGHT_MISSING -> R.string.profile_err_height_missing
    ProfileError.HEIGHT_OUT_OF_RANGE -> R.string.profile_err_height_range
    ProfileError.WEIGHT_MISSING -> R.string.profile_err_weight_missing
    ProfileError.WEIGHT_OUT_OF_RANGE -> R.string.profile_err_weight_range
}
