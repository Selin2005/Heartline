package com.heartline.phone.ui.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardTitle
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme

/** Intended use, disclaimer and open-source notices. */
@Composable
fun AboutScreen(versionName: String, onBack: (() -> Unit)? = null) {
    val colors = HeartlineTheme.colors
    ReachabilityScaffold(title = stringResource(R.string.settings_about), subtitle = stringResource(R.string.settings_version, versionName), onBack = onBack) {
        listOf(
            R.string.about_use_title to R.string.about_use_body,
            R.string.settings_disclaimer to R.string.about_disclaimer_body,
            R.string.about_privacy_title to R.string.about_privacy_body,
            R.string.settings_licenses to R.string.about_licenses_body,
        ).forEach { (title, body) ->
            item {
                RoundedCard(Modifier.gutter()) {
                    CardTitle(stringResource(title))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                }
            }
        }
    }
}
