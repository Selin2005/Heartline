// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heartline.phone.R
import com.heartline.phone.ui.theme.Dimens
import com.heartline.phone.ui.theme.HeartlineTheme

/**
 * Set while a screen was opened on its own from outside the app (a widget…): the app bar then
 * also offers a way into the app's home, since Back leaves the app.
 */
val LocalOpenAppHome = compositionLocalOf<(() -> Unit)?> { null }

/**
 * One UI "reachability" layout: a tall header holding a large title in the top third of the
 * screen that scrolls away, leaving a compact app bar whose title fades in.
 */
@Composable
fun ReachabilityScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    content: LazyListScope.() -> Unit,
) {
    val colors = HeartlineTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val headerHeight = maxHeight * 0.30f
        val headerPx = with(LocalDensity.current) { headerHeight.toPx() }
        val collapse by remember(listState, headerPx) {
            derivedStateOf {
                if (listState.firstVisibleItemIndex > 0) {
                    1f
                } else {
                    (listState.firstVisibleItemScrollOffset / (headerPx * 0.6f)).coerceIn(0f, 1f)
                }
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "reachability-header") {
                Column(
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(headerHeight)
                        .padding(horizontal = 28.dp, vertical = 20.dp)
                        .alpha(1f - collapse),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.displayMedium,
                        color = colors.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            content()
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background.copy(alpha = collapse))
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = colors.onBackground,
                    )
                }
            } else {
                Spacer(Modifier.width(Dimens.gutter - 4.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp).alpha(collapse),
            )
            actions()
            LocalOpenAppHome.current?.let { openHome ->
                IconButton(onClick = openHome) {
                    Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.action_open_home), tint = colors.onBackground)
                }
            }
        }
    }
}

/** Standard horizontal inset for items placed in a [ReachabilityScaffold]. */
fun Modifier.gutter(): Modifier = padding(horizontal = Dimens.gutter)
