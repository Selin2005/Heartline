// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.tile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.remote.creation.compose.action.Action
import androidx.compose.remote.creation.compose.action.pendingIntentAction
import androidx.compose.remote.creation.compose.capture.RemoteImageVector
import androidx.compose.remote.creation.compose.capture.toRemoteImageVector
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteArrangement
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteColumn
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.layout.RemoteRow
import androidx.compose.remote.creation.compose.layout.RemoteText
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.background
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.clip
import androidx.compose.remote.creation.compose.modifier.fillMaxHeight
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.modifier.fillMaxWidth
import androidx.compose.remote.creation.compose.modifier.height
import androidx.compose.remote.creation.compose.modifier.padding
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.modifier.width
import androidx.compose.remote.creation.compose.shapes.RemoteCircleShape
import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape
import androidx.compose.remote.creation.compose.state.rc
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.remote.creation.compose.state.rf
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.glance.wear.AssociateWithGlanceWearWidget
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.GlanceWearWidgetService
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.ContainerInfo
import androidx.glance.wear.core.WearWidgetParams
import androidx.wear.compose.remote.material3.RemoteIcon
import com.heartline.shared.model.Metric
import com.heartline.shared.nav.EntryLinks
import com.heartline.shared.nav.EntrySource
import com.heartline.wear.MainActivity
import org.koin.mp.KoinPlatform

/*
 * Heartline's cards for the watch's tile stack (Wear widgets). A card is SMALL (a slim pill, two
 * fit on one screen) or LARGE (a short card); several apps' cards stack in one scrolling page.
 * No buttons: tapping the card opens its screen (a few cards have tappable icons as content).
 * On watches without the stack, the same card is shown as a full-screen tile.
 */

/** How much room a card has: the stack's slim or large card, or a whole round screen (older watches). */
enum class CardSize {
    SMALL,
    LARGE,
    FULL;

    companion object {
        fun of(containerType: Int): CardSize = when (containerType) {
            ContainerInfo.CONTAINER_TYPE_SMALL -> SMALL
            ContainerInfo.CONTAINER_TYPE_LARGE -> LARGE
            else -> FULL
        }
    }
}

/** A card's data comes from the watch's own stores; [sample] replaces it for previews and tests. */
abstract class HeartlineCard(private val kind: CardKind, private val sample: TileData? = null) : GlanceWearWidget() {
    override suspend fun provideWidgetData(context: Context, params: WearWidgetParams): WearWidgetData {
        val data = sample ?: runCatching { KoinPlatform.getKoin().get<TileDataLoader>().load() }.getOrNull() ?: TileData(null, null, null, null)
        val model = CardModels.of(context, kind, data)
        val icons = Metric.entries.associateWith { ImageVector.vectorResource(null, context.resources, TileIcons.card(it)).toRemoteImageVector() }
        val size = CardSize.of(params.containerType)
        return WearWidgetDocument(background = WearWidgetBrush.color(Color(CardColors.BACKGROUND).rc)) {
            CardLayout(model, size, icons, open = { route -> openAction(route) })
        }
    }

    companion object {
        /** Opens [route] in the app, marked as coming from a tile so Back returns to the stack. */
        @Composable
        fun openAction(route: String): Action = pendingIntentAction { context ->
            PendingIntent.getActivity(
                context,
                route.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, EntryLinks.tag(route, EntrySource.TILE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

private fun c(argb: Long) = Color(argb).rc

private fun tint(argb: Long, alpha: Float) = Color(argb).copy(alpha = alpha).rc

@RemoteComposable
@Composable
internal fun CardLayout(m: CardModel, size: CardSize, icons: Map<Metric, RemoteImageVector>, open: @Composable (String) -> Action) {
    when (size) {
        CardSize.SMALL -> SmallCard(m, icons, open)
        CardSize.LARGE -> LargeCard(m, icons, open)
        // A whole round screen: the large card, kept inside the circle.
        CardSize.FULL -> LargeCard(m, icons, open, full = true)
    }
}

/** Slim pill: icon, the value with its status under it, and a few bars at the end. */
@RemoteComposable
@Composable
private fun SmallCard(m: CardModel, icons: Map<Metric, RemoteImageVector>, open: @Composable (String) -> Action) {
    RemoteRow(
        RemoteModifier.fillMaxSize().clickable(open(m.route)).padding(horizontal = 8.rdp),
        horizontalArrangement = RemoteArrangement.spacedBy(8.rdp),
        verticalAlignment = RemoteAlignment.CenterVertically,
    ) {
        if (m.items.isNotEmpty() && !m.hasValue) {
            // Shortcut and wellness cards: just the icons, each opening its own measurement.
            m.items.take(if (m.kind == CardKind.MEASURE) 4 else 3).forEach { ItemIcon(it, icons, open, 34) }
            return@RemoteRow
        }
        Badge(m.metric, icons, m.accent, 34)
        RemoteColumn(RemoteModifier.weight(1f.rf)) {
            if (m.hasValue) {
                ValueLine(m, 20)
                Line(m.status ?: m.title, m.statusColor ?: CardColors.SUBTLE, 11)
            } else {
                Line(m.title, CardColors.TEXT, 13, FontWeight.Medium)
                Line(m.status ?: m.detail ?: "–", CardColors.SUBTLE, 11)
            }
        }
        if (m.items.isNotEmpty()) {
            m.items.forEach { Dot(if (it.done) CardColors.metric(it.metric) else CardColors.TRACK, 8) }
        } else if (m.bars.count { it != null } >= 2) {
            Bars(m.bars.takeLast(8), m.barRange, m.accent, height = 22, width = 4)
        }
    }
}

/** Short card: icon and title, the value large, its status and detail, and bars or items. */
@RemoteComposable
@Composable
private fun LargeCard(m: CardModel, icons: Map<Metric, RemoteImageVector>, open: @Composable (String) -> Action, full: Boolean = false) {
    // On a whole round screen the content sits in the middle, clear of the curved edge.
    RemoteColumn(
        if (full) {
            RemoteModifier.fillMaxSize().clickable(open(m.route)).padding(horizontal = 28.rdp, vertical = 30.rdp)
        } else {
            RemoteModifier.fillMaxSize().clickable(open(m.route)).padding(horizontal = 12.rdp, vertical = 8.rdp)
        },
        verticalArrangement = if (full) RemoteArrangement.Center else RemoteArrangement.spacedBy(2.rdp),
        horizontalAlignment = if (full) RemoteAlignment.CenterHorizontally else RemoteAlignment.Start,
    ) {
        RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically, horizontalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
            if (m.kind != CardKind.TODAY) RemoteIcon(icons.getValue(m.metric), contentDescription = null, modifier = RemoteModifier.size(16.rdp), tint = c(m.accent))
            Line(m.title, CardColors.TEXT, 13, FontWeight.Medium)
        }
        if (m.items.isNotEmpty()) {
            if (m.hasValue) ValueLine(m, 22)
            // Shortcuts come in rows of three; wellness and today are one row.
            m.items.take(if (m.kind == CardKind.MEASURE) 6 else 3).chunked(3).forEach { row ->
                RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.SpaceEvenly, verticalAlignment = RemoteAlignment.CenterVertically) {
                    row.forEach { item ->
                        RemoteColumn(horizontalAlignment = RemoteAlignment.CenterHorizontally) {
                            ItemIcon(item, icons, open, if (m.kind == CardKind.MEASURE) 28 else 32)
                            item.value?.let { Line(it, CardColors.TEXT, 13, FontWeight.Bold) }
                        }
                    }
                }
            }
            (m.status ?: m.detail)?.let { Line(it, CardColors.SUBTLE, 11) }
            return@RemoteColumn
        }
        if (m.hasValue) ValueLine(m, if (full) 36 else 30) else Line(m.detail ?: "–", CardColors.SUBTLE, 12)
        m.status?.let { Line(it, m.statusColor ?: CardColors.SUBTLE, 12) }
        if (m.bars.count { it != null } >= 2) {
            Bars(m.bars, m.barRange, m.accent, height = 20, width = 5)
        } else if (m.hasValue) {
            m.detail?.let { Line(it, CardColors.SUBTLE, 11) }
        }
    }
}

@RemoteComposable
@Composable
private fun ValueLine(m: CardModel, size: Int) {
    RemoteRow(verticalAlignment = RemoteAlignment.Bottom, horizontalArrangement = RemoteArrangement.spacedBy(3.rdp)) {
        RemoteText(m.value.orEmpty().rs, color = c(CardColors.TEXT), fontSize = size.rsp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.unit?.let { RemoteText(it.rs, color = c(CardColors.SUBTLE), fontSize = (size * 0.45f).toInt().coerceAtLeast(10).rsp, maxLines = 1) }
    }
}

@RemoteComposable
@Composable
private fun Line(text: String, color: Long, size: Int, weight: FontWeight = FontWeight.Normal) {
    RemoteText(text.rs, color = c(color), fontSize = size.rsp, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@RemoteComposable
@Composable
private fun Badge(metric: Metric, icons: Map<Metric, RemoteImageVector>, color: Long, size: Int) {
    RemoteBox(RemoteModifier.size(size.rdp).clip(RemoteCircleShape).background(tint(color, 0.22f)), contentAlignment = RemoteAlignment.Center) {
        RemoteIcon(icons.getValue(metric), contentDescription = null, modifier = RemoteModifier.size((size * 0.55f).toInt().rdp), tint = c(color))
    }
}

/** A round icon that opens its own measurement; filled when that check-in is done today. */
@RemoteComposable
@Composable
private fun ItemIcon(item: CardItem, icons: Map<Metric, RemoteImageVector>, open: @Composable (String) -> Action, size: Int) {
    val color = CardColors.metric(item.metric)
    RemoteBox(
        RemoteModifier.size(size.rdp).clip(RemoteCircleShape).background(if (item.done) c(color) else tint(color, 0.22f)).clickable(open(item.route)),
        contentAlignment = RemoteAlignment.Center,
    ) {
        RemoteIcon(icons.getValue(item.metric), contentDescription = item.label.rs, modifier = RemoteModifier.size((size * 0.55f).toInt().rdp), tint = if (item.done) c(0xFF000000) else c(color))
    }
}

@RemoteComposable
@Composable
private fun Dot(color: Long, size: Int) {
    RemoteBox(RemoteModifier.size(size.rdp).clip(RemoteCircleShape).background(c(color)))
}

/** Small rounded bars, bottom-aligned; the newest in full colour, older ones dimmer, gaps for no data. */
@RemoteComposable
@Composable
private fun Bars(values: List<Float?>, range: ClosedFloatingPointRange<Float>, color: Long, height: Int, width: Int) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    RemoteRow(RemoteModifier.height(height.rdp), horizontalArrangement = RemoteArrangement.spacedBy(3.rdp), verticalAlignment = RemoteAlignment.Bottom) {
        values.forEachIndexed { i, v ->
            val h = v?.let { (((it - range.start) / span).coerceIn(0f, 1f) * height).coerceAtLeast(width.toFloat()) } ?: 0f
            RemoteBox(RemoteModifier.width(width.rdp).fillMaxHeight(), contentAlignment = RemoteAlignment.BottomCenter) {
                if (v != null) {
                    RemoteBox(
                        RemoteModifier.size(width.rdp, h.rdp).clip(RemoteRoundedCornerShape((width / 2f).rdp))
                            .background(if (i == values.lastIndex) c(color) else tint(color, 0.5f)),
                    )
                }
            }
        }
    }
}

class HeartCard(sample: TileData? = null) : HeartlineCard(CardKind.HEART, sample)

class BpCard(sample: TileData? = null) : HeartlineCard(CardKind.BLOOD_PRESSURE, sample)

class EcgCard(sample: TileData? = null) : HeartlineCard(CardKind.ECG, sample)

class Spo2Card(sample: TileData? = null) : HeartlineCard(CardKind.SPO2, sample)

class StressCard(sample: TileData? = null) : HeartlineCard(CardKind.STRESS, sample)

class BodyCard(sample: TileData? = null) : HeartlineCard(CardKind.BODY, sample)

class TodayCard(sample: TileData? = null) : HeartlineCard(CardKind.TODAY, sample)

class WellnessCard(sample: TileData? = null) : HeartlineCard(CardKind.WELLNESS, sample)

class MeasureCard(sample: TileData? = null) : HeartlineCard(CardKind.MEASURE, sample)

/*
 * One service per card, bound both as a widget (the tile stack) and as a tile (older watches).
 * The class names are those of the earlier tiles, so cards already added stay in place.
 */

@AssociateWithGlanceWearWidget(HeartCard::class)
class HeartTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = HeartCard()
}

@AssociateWithGlanceWearWidget(BpCard::class)
class BpTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = BpCard()
}

@AssociateWithGlanceWearWidget(EcgCard::class)
class EcgTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = EcgCard()
}

@AssociateWithGlanceWearWidget(Spo2Card::class)
class Spo2TileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = Spo2Card()
}

@AssociateWithGlanceWearWidget(StressCard::class)
class StressTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = StressCard()
}

@AssociateWithGlanceWearWidget(BodyCard::class)
class BodyTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = BodyCard()
}

@AssociateWithGlanceWearWidget(TodayCard::class)
class TodayTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = TodayCard()
}

@AssociateWithGlanceWearWidget(WellnessCard::class)
class WellnessTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = WellnessCard()
}

@AssociateWithGlanceWearWidget(MeasureCard::class)
class QuickMeasureTileService : GlanceWearWidgetService() {
    override val widget: GlanceWearWidget = MeasureCard()
}

/** Every card, for updates. */
internal val ALL_CARDS: List<() -> GlanceWearWidget> = listOf(::HeartCard, ::BpCard, ::EcgCard, ::Spo2Card, ::StressCard, ::BodyCard, ::TodayCard, ::WellnessCard, ::MeasureCard).map { f -> { f(null) } }
