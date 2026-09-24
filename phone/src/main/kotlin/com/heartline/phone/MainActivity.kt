package com.heartline.phone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.heartline.phone.notify.PhoneNotifier
import com.heartline.phone.ui.model.OnboardingViewModel
import com.heartline.phone.ui.onboarding.OnboardingFlow
import org.koin.androidx.compose.koinViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.heartline.phone.ui.HeartlineApp
import com.heartline.phone.ui.theme.HeartlineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                when (onboarded) {
                    null -> Unit
                    false -> OnboardingFlow(onFinished = onboarding::finish)
                    true -> HeartlineApp(openAlerts = openAlerts)
                }
            }
        }
    }
}
