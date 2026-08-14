package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxtrader.app.R
import com.foxtrader.app.domain.usecase.chart.ChartScaleMode
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Mobile chart navigation rail.
 *
 * Touch-only charts benefit from explicit zoom/reset affordances: they are
 * discoverable, TalkBack-addressable, and do not steal a gesture from the
 * primary canvas. The actions call the same viewport camera math as pinch zoom
 * and double-tap reset, so there is no second behavior to drift.
 */
@Composable
internal fun ChartNavigationControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetToLatest: () -> Unit,
    scaleMode: ChartScaleMode,
    onToggleScaleMode: () -> Unit,
    logScaleAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(end = 78.dp, bottom = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FoxNeutral10.copy(alpha = 0.9f))
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        NavigationButton(
            icon = Icons.Default.Add,
            description = stringResource(R.string.chart_zoom_in),
            onClick = onZoomIn,
        )
        NavigationButton(
            icon = Icons.Default.Remove,
            description = stringResource(R.string.chart_zoom_out),
            onClick = onZoomOut,
        )
        NavigationButton(
            icon = Icons.Default.MyLocation,
            description = stringResource(R.string.chart_reset_to_latest),
            onClick = onResetToLatest,
        )
        ScaleModeButton(
            scaleMode = scaleMode,
            enabled = logScaleAvailable,
            onClick = onToggleScaleMode,
        )
    }
}

@Composable
private fun ScaleModeButton(
    scaleMode: ChartScaleMode,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(
        if (scaleMode == ChartScaleMode.LOGARITHMIC) {
            R.string.chart_scale_log_label
        } else {
            R.string.chart_scale_auto_label
        },
    )
    val description = stringResource(
        if (!enabled) R.string.chart_scale_log_unavailable
        else if (scaleMode == ChartScaleMode.LOGARITHMIC) R.string.chart_scale_switch_linear
        else R.string.chart_scale_switch_log,
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = description },
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = if (enabled && scaleMode == ChartScaleMode.LOGARITHMIC) FoxAmber50 else FoxNeutral60,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

@Composable
private fun NavigationButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (icon == Icons.Default.MyLocation) FoxAmber50 else FoxNeutral60,
            modifier = Modifier.size(18.dp),
        )
    }
}
