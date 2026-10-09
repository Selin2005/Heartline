// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.widget

import com.heartline.datalayer.diag.HLog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import com.heartline.datalayer.DeepLinks
import com.heartline.phone.R
import com.heartline.phone.link.OpenResult
import com.heartline.phone.link.WatchOpener
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform

/** Opens the phone app on [route] (heartline://phone/<route>); Back then returns to the home screen. */
fun openApp(context: Context, route: String): Action = actionStartActivity(appIntent(context, route))

internal fun appIntent(context: Context, route: String, source: EntrySource = EntrySource.WIDGET): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.phone(EntryLinks.tag(route, source))))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** Opens the app's measurement screen [phoneRoute] (it starts the measurement on the watch and follows it). */
fun measureOnPhone(phoneRoute: String): Action = actionRunCallback<MeasureOnPhoneAction>(actionParametersOf(MeasureOnWatchAction.PHONE to phoneRoute))

class MeasureOnPhoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val route = parameters[MeasureOnWatchAction.PHONE] ?: return
        HLog.i("Heartline/Widget", "measure from the phone: $route")
        withContext(Dispatchers.Main) { context.startActivity(appIntent(context, route)) }
    }
}

/** Opens [watchRoute] on the watch; without a watch, opens [phoneRoute] in the app instead. */
fun measureOnWatch(watchRoute: String, phoneRoute: String): Action = actionRunCallback<MeasureOnWatchAction>(
    actionParametersOf(MeasureOnWatchAction.WATCH to watchRoute, MeasureOnWatchAction.PHONE to phoneRoute),
)

class MeasureOnWatchAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val route = parameters[WATCH] ?: return
        val result = runCatching { KoinPlatform.getKoin().get<WatchOpener>().open(route) }.getOrDefault(OpenResult.NO_WATCH)
        HLog.i("Heartline/Widget", "measure on watch: $route -> $result")
        withContext(Dispatchers.Main) {
            when (result) {
                OpenResult.OPENED -> Toast.makeText(context, R.string.widget_opened_on_watch, Toast.LENGTH_SHORT).show()
                OpenResult.NOTIFIED -> Toast.makeText(context, R.string.widget_check_watch, Toast.LENGTH_SHORT).show()
                OpenResult.NO_WATCH -> {
                    Toast.makeText(context, R.string.widget_no_watch, Toast.LENGTH_SHORT).show()
                    context.startActivity(appIntent(context, parameters[PHONE] ?: ""))
                }
            }
        }
    }

    companion object {
        val WATCH = ActionParameters.Key<String>("watchRoute")
        val PHONE = ActionParameters.Key<String>("phoneRoute")
    }
}
