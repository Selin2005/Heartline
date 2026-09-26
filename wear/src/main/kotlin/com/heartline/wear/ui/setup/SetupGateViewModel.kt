// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.ui.setup

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heartline.shared.model.Metric
import com.heartline.shared.sensor.PermissionPolicy
import com.heartline.shared.sync.LinkStage
import com.heartline.shared.sync.PhoneStatus
import com.heartline.shared.sync.SetupTarget
import com.heartline.shared.sync.WatchLinkChecker
import com.heartline.wear.link.PhoneOpener
import com.heartline.wear.link.WatchLinkStore
import com.heartline.wear.sensor.SensorGateway
import com.heartline.wear.sensor.SensorProblem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Everything the watch checks before showing its features, in order. */
sealed interface GateState {
    data object CheckingPhone : GateState

    /** No usable link to the phone app (and the watch was never set up with one). */
    data class PhoneProblem(val stage: LinkStage) : GateState

    /** The phone is connected but its onboarding/profile isn't finished. */
    data class SetupIncomplete(val status: PhoneStatus) : GateState

    data object NeedsPermissions : GateState

    data object CheckingSensors : GateState

    /** Health Platform problem found by the probe; SDK_POLICY means developer mode is off. */
    data class SensorIssue(val problem: SensorProblem) : GateState

    /** [offline]: the phone is away right now, but this watch was set up with it before. */
    data class Ready(val offline: Boolean) : GateState
}

/**
 * Runs the watch's setup checks: phone link and handshake, phone-side setup, sensor permissions,
 * then a Health Platform probe (catches developer mode being off before any feature is opened).
 */
class SetupGateViewModel(
    private val checker: WatchLinkChecker,
    private val store: WatchLinkStore,
    private val gateway: SensorGateway,
    private val phone: PhoneOpener,
    private val permissionsGranted: () -> Boolean
) : ViewModel() {
    private val mutable = MutableStateFlow<GateState>(GateState.CheckingPhone)
    val state: StateFlow<GateState> = mutable.asStateFlow()

    private val opened = MutableStateFlow<Boolean?>(null)

    /** Result of the last "Open on phone" tap: true = opened there, false = couldn't reach the phone. */
    val openedOnPhone: StateFlow<Boolean?> = opened.asStateFlow()

    private var job: Job? = null

    /** Whether the last link check ran without the phone (set up before, phone away now). */
    private var offline = false

    init {
        // Finishing setup on the phone pushes a new status: continue without the user doing anything here.
        viewModelScope.launch {
            store.latest.filterNotNull().collect { stamped ->
                if (mutable.value is GateState.SetupIncomplete && stamped.status.setupComplete) {
                    offline = false
                    afterPhone()
                }
            }
        }
    }

    /** Runs all checks. When already [GateState.Ready], re-checks quietly and only interrupts for a real problem. */
    fun check() {
        if (job?.isActive == true) return
        val quiet = mutable.value is GateState.Ready
        job = viewModelScope.launch {
            if (!quiet) mutable.value = GateState.CheckingPhone
            val link = checker.check()
            Log.i(TAG, "link=${link.stage} status=${link.status}")
            val status = link.status
            when {
                link.stage == LinkStage.CONNECTED && status != null && !status.setupComplete -> mutable.value = GateState.SetupIncomplete(status)
                link.stage == LinkStage.CONNECTED -> proceed(quiet, offline = false)
                // Set up before: let the user measure; results wait in the outbox until the phone is back.
                store.wasSetUp && link.stage != LinkStage.INCOMPATIBLE -> proceed(quiet, offline = true)
                else -> mutable.value = GateState.PhoneProblem(link.stage)
            }
        }
    }

    private suspend fun proceed(quiet: Boolean, offline: Boolean) {
        this.offline = offline
        if (quiet) mutable.value = GateState.Ready(offline) else afterPhone()
    }

    private suspend fun afterPhone() {
        if (!permissionsGranted()) {
            mutable.value = GateState.NeedsPermissions
            return
        }
        mutable.value = GateState.CheckingSensors
        val problem = gateway.probeHealth()
        mutable.value = if (problem == null) GateState.Ready(offline) else GateState.SensorIssue(problem)
    }

    /** After the system permission dialog. */
    fun onPermissionsResult() {
        viewModelScope.launch { afterPhone() }
    }

    /** "Check again" on the developer-mode guide and other sensor problems. */
    fun recheckSensors() {
        viewModelScope.launch { afterPhone() }
    }

    fun openOnPhone(target: SetupTarget) {
        viewModelScope.launch {
            opened.value = phone.open(target)
            // Opening the phone app is what fixes "no response": look again once it has started.
            if (opened.value == true && mutable.value is GateState.PhoneProblem) {
                delay(RECHECK_AFTER_OPEN_MS)
                check()
            }
        }
    }

    fun clearOpened() {
        opened.value = null
    }

    private companion object {
        const val TAG = "Heartline/Setup"
        const val RECHECK_AFTER_OPEN_MS = 4_000L
    }
}

/** Asked for once, during setup, so no feature has to interrupt the user for permissions later. */
object SetupPermissions {
    /** Sensor access: every feature needs it, so the gate waits for it. */
    val required: Array<String>
        get() = Metric.entries.flatMap { PermissionPolicy.permissionsFor(it, Build.VERSION.SDK_INT) }.distinct().toTypedArray()

    /** Also asked for, but optional (alerts, activity context). */
    val requested: Array<String>
        get() = (
            required.toList() +
                Manifest.permission.ACTIVITY_RECOGNITION +
                (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            ).distinct().toTypedArray()
}
