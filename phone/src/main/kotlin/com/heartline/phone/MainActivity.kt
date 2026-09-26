// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import com.heartline.phone.link.PhoneRoutes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heartline.datalayer.DeepLinks
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.phone.ui.model.OnboardingViewModel
import com.heartline.phone.ui.onboarding.OnboardingFlow
import com.heartline.phone.ui.legal.TermsGate
import com.heartline.shared.AppInfo
import org.koin.androidx.compose.koinViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.heartline.phone.ui.HeartlineApp
import com.heartline.phone.ui.theme.HeartlineTheme

class MainActivity : ComponentActivity() {
    /** heartline://phone/<route> from the watch or a notification; consumed once navigated. */
    private var deepLink by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkOf(intent)?.let { deepLink = it }
    }

    /** A heartline:// link, or the settings for a long-pressed Quick Settings tile. */
    private fun linkOf(intent: Intent?): String? =
        if (intent?.action == TileService.ACTION_QS_TILE_PREFERENCES) PhoneRoutes.SETTINGS else DeepLinks.route(intent?.data, DeepLinks.PHONE_HOST)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink = linkOf(intent)
        val openAlerts = intent?.getBooleanExtra(PhoneNotifier.EXTRA_OPEN_ALERTS, false) == true
        setContent {
            HeartlineTheme {
                // Heart alerts are mirrored as phone notifications (Android 13+ needs consent).
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val onboarding: OnboardingViewModel = koinViewModel()
                val onboarded by onboarding.onboarded.collectAsStateWithLifecycle()
                val acceptedTerms by onboarding.acceptedTerms.collectAsStateWithLifecycle()
                val terms = acceptedTerms
                when {
                    onboarded == null || terms == null -> Unit
                    // Before anything else, and again whenever the terms change.
                    terms < AppInfo.TERMS_VERSION -> TermsGate(updated = terms > 0, onAccept = onboarding::acceptTerms, onDecline = ::finish)
                    onboarded == false -> OnboardingFlow(onFinished = onboarding::finish)
                    else -> HeartlineApp(openAlerts = openAlerts, deepLink = deepLink, onDeepLinkHandled = { deepLink = null })
                }
            }
        }
    }
}
