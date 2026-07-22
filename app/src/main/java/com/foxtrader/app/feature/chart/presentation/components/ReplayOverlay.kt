package com.foxtrader.app.feature.chart.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.domain.model.ReplayState
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Replay mode control bar — overlay at the bottom of the chart.
 *
 * Shows:
 * - Play/Pause button
 * - Step forward/backward buttons
 * - Speed indicator + cycle button
 * - Progress bar (current bar / total bars)
 * - Close (exit replay) button
 *
 * Compact, premium design. Does not block chart interaction.
 */
@Composable
fun ReplayControlBar(
    state: ReplayState,
    onPlayPause: () -> Unit,
    onStepForward: () -> Unit,
    onStepBackward: () -> Unit,
    onCycleSpeed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isActive) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Close button
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Exit replay",
                    tint = FoxNeutral60,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Step backward
            IconButton(onClick = onStepBackward, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Step back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Play / Pause (larger, primary)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(FoxAmber50)
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (state.isPaused) "Play" else "Pause",
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(22.dp),
                )
            }

            // Step forward
            IconButton(onClick = onStepForward, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Step forward",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Speed button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onCycleSpeed() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = state.speed.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FoxAmber50,
                )
            }

            // Progress bar (fills remaining space)
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = FoxAmber50,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // Bar counter
            Text(
                text = "${state.currentIndex}/${state.totalBars}",
                style = MaterialTheme.typography.labelSmall,
                color = FoxNeutral60,
            )
        }
    }
}
