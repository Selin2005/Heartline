package com.heartline.phone.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.Chip
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.profile.Sex
import com.heartline.shared.profile.UserProfile

@Composable
fun ProfileScreen(profile: UserProfile?, onBack: (() -> Unit)? = null, onSave: (UserProfile) -> Unit = {}) {
    val colors = HeartlineTheme.colors
    var year by remember(profile) { mutableStateOf(profile?.birthYear?.toString().orEmpty()) }
    var sex by remember(profile) { mutableStateOf(profile?.sex) }
    var height by remember(profile) { mutableStateOf(profile?.heightCm?.toInt()?.toString().orEmpty()) }
    var weight by remember(profile) { mutableStateOf(profile?.weightKg?.toString().orEmpty()) }
    val candidate = sex?.let { s ->
        val y = year.toIntOrNull()
        val h = height.toFloatOrNull()
        val w = weight.toFloatOrNull()
        if (y != null && h != null && w != null) UserProfile(y, s, h, w) else null
    }
    ReachabilityScaffold(title = stringResource(R.string.settings_profile), onBack = onBack) {
        item {
            RoundedCard(Modifier.gutter()) {
                Text(stringResource(R.string.profile_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.profile_sex), style = MaterialTheme.typography.titleSmall, color = colors.onBackground)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.profile_female), sex == Sex.FEMALE) { sex = Sex.FEMALE }
                    Chip(stringResource(R.string.profile_male), sex == Sex.MALE) { sex = Sex.MALE }
                }
                Spacer(Modifier.height(16.dp))
                ProfileField(stringResource(R.string.profile_birth_year), year, { year = it.filter(Char::isDigit).take(4) })
                ProfileField(stringResource(R.string.profile_height), height, { height = it.filter(Char::isDigit).take(3) })
                ProfileField(stringResource(R.string.profile_weight), weight, { weight = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, decimal = true)
                if (candidate != null && !candidate.isComplete) {
                    Text(stringResource(R.string.profile_invalid), style = MaterialTheme.typography.bodySmall, color = colors.statusAlert)
                }
                Spacer(Modifier.height(16.dp))
                PillButton(
                    stringResource(R.string.action_save),
                    onClick = { candidate?.takeIf { it.isComplete }?.let(onSave) },
                )
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, onChange: (String) -> Unit, decimal: Boolean = false) {
    val colors = HeartlineTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.divider,
            unfocusedContainerColor = colors.surfaceVariant.copy(alpha = 0.4f),
            focusedContainerColor = colors.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}
