// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.phone.R
import com.heartline.phone.link.WatchLinkUi
import com.heartline.phone.ui.components.WatchLinkCard
import com.heartline.phone.ui.model.ProfileViewModel
import com.heartline.phone.ui.model.WatchLinkViewModel
import com.heartline.phone.ui.profile.ProfileScreen
import com.heartline.shared.sync.PeerProbe
import org.koin.androidx.compose.koinViewModel
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.icon
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.shared.model.Metric

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit = {}) {
    val colors = HeartlineTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.weight(0.6f))
        HeroHeart()
        Spacer(Modifier.height(36.dp))
        Text(
            stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.displaySmall,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            Point(Metric.ECG, R.string.onboarding_point_ecg)
            Point(Metric.BLOOD_PRESSURE, R.string.onboarding_point_bp)
            Point(Metric.HEART_RATE, R.string.onboarding_point_irn)
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.not_a_diagnosis),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.action_get_started), onClick = onGetStarted)
    }
}

@Composable
private fun Point(metric: Metric, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(metric.icon, HeartlineTheme.colors.metric(metric), size = 36)
        Spacer(Modifier.width(14.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = HeartlineTheme.colors.onBackground)
    }
}

/** Original illustration: layered glow rings behind a heart, with an ECG trace across it. */
@Composable
private fun HeroHeart() {
    val ecg = HeartlineTheme.colors.ecg
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(ecg.copy(alpha = 0.28f), Color.Transparent)), radius = size.minDimension / 2)
            drawCircle(ecg.copy(alpha = 0.16f), radius = size.minDimension * 0.34f)
        }
        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = ecg, modifier = Modifier.size(84.dp))
        Canvas(Modifier.size(120.dp, 40.dp)) {
            val h = size.height
            val w = size.width
            val pts = listOf(0f to 0.5f, 0.3f to 0.5f, 0.38f to 0.3f, 0.45f to 0.95f, 0.52f to 0.05f, 0.6f to 0.6f, 0.68f to 0.5f, 1f to 0.5f)
            for (i in 0 until pts.size - 1) {
                drawLine(
                    Color.White,
                    Offset(pts[i].first * w, pts[i].second * h),
                    Offset(pts[i + 1].first * w, pts[i + 1].second * h),
                    strokeWidth = 3.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

/** First run: welcome, the profile, the monitoring setup, then connecting the watch, then the app. */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    when (step) {
        0 -> OnboardingScreen(onGetStarted = { step = 1 })
        1 -> {
            val vm: ProfileViewModel = koinViewModel()
            val profile by vm.profile.collectAsStateWithLifecycle()
            ProfileScreen(profile = profile, onBack = { step = 0 }, onSave = { vm.save(it) { step = 2 } })
        }
        2 -> MonitoringSetupRoute(onDone = { step = 3 }, onBack = { step = 1 })
        else -> {
            val vm: WatchLinkViewModel = koinViewModel()
            val link by vm.link.collectAsStateWithLifecycle()
            LifecycleResumeEffect(Unit) {
                vm.refresh()
                onPauseOrDispose {}
            }
            ConnectWatchScreen(link, onRetry = vm::refresh, onOpenWatch = vm::openWatchApp, onContinue = onFinished)
        }
    }
}

@Composable
fun ConnectWatchScreen(link: WatchLinkUi?, onRetry: () -> Unit = {}, onOpenWatch: () -> Unit = {}, onContinue: () -> Unit = {}) {
    val colors = HeartlineTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Spacer(Modifier.weight(0.4f))
        Text(stringResource(R.string.onboarding_watch_title), style = MaterialTheme.typography.displaySmall, color = colors.onBackground)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_watch_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        WatchLinkCard(link, onRetry = onRetry, onOpenWatch = onOpenWatch)
        Spacer(Modifier.weight(1f))
        val ready = link?.probe == PeerProbe.REACHABLE
        PillButton(stringResource(if (ready) R.string.action_continue else R.string.action_skip_for_now), onClick = onContinue)
    }
}
