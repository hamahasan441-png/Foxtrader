package com.foxtrader.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.ui.theme.FoxTheme

@Composable
fun FoxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentBar: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FoxTheme.colors
    val shape = RoundedCornerShape(FoxTheme.shapes.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (accentBar != null) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accentBar),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(FoxTheme.spacing.card),
            content = content,
        )
    }
}

@Composable
fun FoxPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FoxTheme.colors
    val shape = RoundedCornerShape(FoxTheme.shapes.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.border, shape)
            .padding(FoxTheme.spacing.card),
        content = content,
    )
}

@Composable
fun FoxMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    caption: String? = null,
) {
    val colors = FoxTheme.colors
    FoxPanel(modifier = modifier) {
        Text(label, style = FoxTheme.type.caption, color = colors.textMuted)
        Spacer(Modifier.height(FoxTheme.spacing.xxs))
        Text(
            text = value,
            style = FoxTheme.type.price,
            color = valueColor ?: colors.textPrimary,
        )
        if (caption != null) {
            Spacer(Modifier.height(FoxTheme.spacing.xxxs))
            Text(caption, style = FoxTheme.type.caption, color = colors.textMuted)
        }
    }
}

@Composable
fun FoxSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = FoxTheme.type.h3,
            color = FoxTheme.colors.accent,
            fontWeight = FontWeight.SemiBold,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = FoxTheme.type.label,
                color = FoxTheme.colors.accent,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoxScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = FoxTheme.colors
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = FoxTheme.type.h2,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                FoxIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onNavigateBack,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            navigationIconContentColor = colors.textPrimary,
            actionIconContentColor = colors.accent,
        ),
    )
}

@Composable
fun FoxBanner(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: FoxBannerTone = FoxBannerTone.Warning,
    icon: ImageVector? = null,
) {
    val colors = FoxTheme.colors
    val accent = when (tone) {
        FoxBannerTone.Warning -> colors.warning
        FoxBannerTone.Danger -> colors.danger
        FoxBannerTone.Info -> colors.information
        FoxBannerTone.Success -> colors.success
    }
    val shape = RoundedCornerShape(FoxTheme.shapes.sm)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .padding(horizontal = FoxTheme.spacing.md, vertical = FoxTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Column {
            if (title != null) {
                Text(title, style = FoxTheme.type.label, color = accent, fontWeight = FontWeight.Bold)
            }
            Text(text, style = FoxTheme.type.caption, color = colors.textSecondary)
        }
    }
}

enum class FoxBannerTone { Warning, Danger, Info, Success }
