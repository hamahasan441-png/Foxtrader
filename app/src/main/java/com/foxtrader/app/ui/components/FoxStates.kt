package com.foxtrader.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foxtrader.app.ui.theme.FoxTheme

@Composable
fun FoxEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    FoxStatusFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun FoxErrorState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String = "Try again",
    onRetry: (() -> Unit)? = null,
) {
    FoxStatusFrame(
        icon = Icons.Outlined.ErrorOutline,
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        actionLabel = if (onRetry != null) actionLabel else null,
        onAction = onRetry,
        tintDanger = true,
    )
}

@Composable
fun FoxOfflineState(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    FoxStatusFrame(
        icon = Icons.Outlined.CloudOff,
        title = "You're offline",
        subtitle = "Cached data stays available. Reconnect to refresh live prices and scans.",
        modifier = modifier,
        actionLabel = if (onRetry != null) "Retry connection" else null,
        onAction = onRetry,
    )
}

@Composable
fun FoxPermissionState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    FoxStatusFrame(
        icon = Icons.Outlined.Lock,
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun FoxLoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = FoxTheme.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
            if (label != null) {
                Spacer(Modifier.height(FoxTheme.spacing.sm))
                Text(label, style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
            }
        }
    }
}

@Composable
private fun FoxStatusFrame(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    tintDanger: Boolean = false,
) {
    val colors = FoxTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FoxTheme.spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tintDanger) colors.danger else colors.accent,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(FoxTheme.spacing.md))
        Text(
            text = title,
            style = FoxTheme.type.h3,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(FoxTheme.spacing.xs))
        Text(
            text = subtitle,
            style = FoxTheme.type.body,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(FoxTheme.spacing.lg))
            FoxButton(
                text = actionLabel,
                onClick = onAction,
                icon = Icons.Outlined.Refresh,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
