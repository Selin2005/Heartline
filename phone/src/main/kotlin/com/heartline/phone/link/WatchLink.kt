// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.link

import android.util.Log
import com.heartline.datalayer.DataLayerTransport
import com.heartline.datalayer.DeepLinks
import com.heartline.datalayer.RemoteOpener
import com.heartline.phone.BuildConfig
import com.heartline.phone.data.BpRepository
import com.heartline.phone.data.ProfileRepository
import com.heartline.phone.data.SettingsRepository
import com.heartline.shared.bp.BpCalibration
import com.heartline.shared.model.Metric
import com.heartline.shared.profile.UserProfile
import com.heartline.shared.sync.CalibrationStatus
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.PhoneStatus
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.SetupTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Builds the [PhoneStatus] the watch gates on, and pushes it whenever it changes. */
class PhoneStatusPublisher(
    settings: SettingsRepository,
    profiles: ProfileRepository,
    bp: BpRepository,
    private val sync: () -> PhoneSyncEngine,
    private val now: () -> Long = System::currentTimeMillis
) {
    val status: Flow<PhoneStatus> = combine(settings.onboarded, settings.termsAccepted, profiles.profile, bp.calibration) { onboarded, terms, profile, calibration ->
        build(onboarded, profile, calibration, now(), terms)
    }.distinctUntilChanged()

    suspend fun current(): PhoneStatus = status.first()

    /** Sends every change after the first value (the first one goes out in reply to the watch's hello). */
    fun start(scope: CoroutineScope) {
        scope.launch {
            status.drop(1).collect {
                Log.i(TAG, "status changed -> $it")
                sync().sendStatus(it)
            }
        }
    }

    companion object {
        private const val TAG = "Heartline/Link"

        fun build(onboarded: Boolean, profile: UserProfile?, calibration: BpCalibration?, nowMs: Long, termsAccepted: Boolean) = PhoneStatus(
            appVersion = BuildConfig.VERSION_NAME,
            onboarded = onboarded,
            termsAccepted = termsAccepted,
            profileComplete = profile?.isComplete == true,
            displayName = profile?.displayName.orEmpty(),
            calcSexKnown = profile?.calcSex != null,
            calibration = when {
                calibration == null -> CalibrationStatus.MISSING
                calibration.isValid(nowMs) -> CalibrationStatus.VALID
                else -> CalibrationStatus.EXPIRED
            },
            calibrationDaysLeft = calibration?.takeIf { it.isValid(nowMs) }?.daysLeft(nowMs)
        )
    }
}

enum class OpenResult { OPENED, NOTIFIED, NO_WATCH }

/**
 * Opens a watch screen immediately via a remote activity launch. When that fails it falls back to
 * the /open message, which the watch shows as a notification that deep-links to the same screen.
 */
class WatchOpener(private val opener: RemoteOpener, private val transport: DataLayerTransport, private val sync: () -> PhoneSyncEngine) {
    suspend fun open(route: String): OpenResult = when {
        opener.open(DeepLinks.watch(route)) -> OpenResult.OPENED
        sync().openOnWatch(route) -> OpenResult.NOTIFIED
        else -> OpenResult.NO_WATCH
    }

    suspend fun probe(): WatchLinkUi {
        val probe = transport.probe()
        val name = if (probe == PeerProbe.REACHABLE) transport.peers().firstOrNull()?.displayName else null
        return WatchLinkUi(probe, name)
    }
}

/** What the phone can see of the watch: a connected watch running Heartline, a watch without it, or none. */
data class WatchLinkUi(val probe: PeerProbe, val name: String? = null)

/** Deep-link routes (heartline://phone/<route>) and where they land in the phone app. */
object PhoneRoutes {
    val HOME = SetupTarget.HOME.phoneRoute
    val PROFILE = SetupTarget.PROFILE.phoneRoute
    val BP_CALIBRATION = SetupTarget.BP_CALIBRATION.phoneRoute
    val DEV_MODE_HELP = SetupTarget.DEV_MODE_HELP.phoneRoute
    const val SETTINGS = "settings"
}

/** Watch navigation routes the phone can open (heartline://watch/<route>). Must match the watch's nav graph. */
object WatchRoutes {
    const val ECG = "ecg"
    const val HEART_RATE = "heart_rate"
    const val BLOOD_PRESSURE = "blood_pressure"
    const val BP_CALIBRATION = "bp_calibration"
    const val SETUP = "setup"

    fun quick(metric: Metric) = "quick/${metric.name}"
}
