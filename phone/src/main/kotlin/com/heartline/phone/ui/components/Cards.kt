package com.heartline.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.heartline.phone.ui.theme.Dimens
import com.heartline.phone.ui.theme.HeartlineTheme

@Composable
fun RoundedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Dimens.cardShape)
            .background(HeartlineTheme.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

private typealias Dp = androidx.compose.ui.unit.Dp

/** A row inside a [RoundedCard] list group (card uses contentPadding = 0.dp). */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
    dividerStart: Dp = if (leading != null) 76.dp else 20.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = HeartlineTheme.colors
    Column(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimens.rowMinHeight)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = colors.divider,
                modifier = Modifier.padding(start = dividerStart, end = 20.dp),
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = HeartlineTheme.colors.onSurfaceVariant,
        modifier = modifier.padding(start = Dimens.gutter + 12.dp, top = 8.dp),
    )
}

/** Tinted circular icon used as a leading element and on metric cards. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (HeartlineTheme.colors.isDark) 0.22f else 0.13f)),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55).dp))
    }
}

/** Large value + unit, e.g. "118/76 mmHg". */
@Composable
fun MetricValue(value: String, unit: String?, modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = HeartlineTheme.colors
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        Text(
            value,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
        )
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                unit,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = if (large) 5.dp else 3.dp),
            )
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val colors = HeartlineTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) androidx.compose.ui.graphics.Color.White else colors.onBackground,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.primary else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = HeartlineTheme.colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun VerticalGap(height: Int) = Spacer(Modifier.height(height.dp))

@Composable
fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = HeartlineTheme.colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = HeartlineTheme.colors.onBackground)
    }
}
