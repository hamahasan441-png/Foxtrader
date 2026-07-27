package com.foxtrader.app.feature.alerts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxError
import com.foxtrader.app.ui.theme.FoxInfo
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Alerts inbox — history, priority filtering, acknowledgement.
 *
 * The alert subsystem shipped with an engine, a dispatcher, a WorkManager
 * worker and a scheduler, but **no UI**: every alert was a transient
 * notification, and `FoxAlert.acknowledged` had nothing that could set it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Alerts", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (state.unreadCount > 0) {
                            Spacer(Modifier.size(8.dp))
                            UnreadBadge(state.unreadCount)
                        }
                    }
                },
                actions = {
                    if (state.hasAlerts) {
                        IconButton(
                            onClick = viewModel::acknowledgeAll,
                            enabled = state.unreadCount > 0,
                        ) {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = "Mark all as read",
                                tint = if (state.unreadCount > 0) FoxAmber50 else FoxNeutral60,
                            )
                        }
                        IconButton(onClick = viewModel::clearAll) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear all alerts",
                                tint = FoxNeutral60,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.hasAlerts) {
                FilterRow(
                    selected = state.priorityFilter,
                    unreadOnly = state.unreadOnly,
                    onSelect = viewModel::setPriorityFilter,
                    onToggleUnread = viewModel::toggleUnreadOnly,
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = FoxAmber50,
                    )

                    !state.hasAlerts -> EmptyInbox(
                        title = "No alerts yet",
                        subtitle = "Approved AI signals and background scan hits " +
                            "will appear here, even if you miss the notification.",
                    )

                    !state.hasVisibleAlerts -> EmptyInbox(
                        title = "Nothing matches this filter",
                        subtitle = "${state.alerts.size} alert(s) hidden. " +
                            "Adjust the filters above to see them.",
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.visibleAlerts, key = { it.id }) { alert ->
                            AlertCard(
                                alert = alert,
                                onAcknowledge = { viewModel.acknowledge(alert.id) },
                                onDelete = { viewModel.delete(alert.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(FoxAmber50)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics { contentDescription = "$count unread alerts" },
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.Black,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FilterRow(
    selected: AlertPriority?,
    unreadOnly: Boolean,
    onSelect: (AlertPriority?) -> Unit,
    onToggleUnread: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            label = "Unread",
            selected = unreadOnly,
            onClick = onToggleUnread,
        )
        FilterChip(
            label = "All",
            selected = selected == null,
            onClick = { onSelect(null) },
        )
        // "and above" — see AlertsUiState.visibleAlerts.
        AlertPriority.entries.forEach { priority ->
            FilterChip(
                label = priority.name.lowercase().replaceFirstChar { it.uppercase() } + "+",
                selected = selected == priority,
                onClick = { onSelect(priority) },
                accent = priorityColor(priority),
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color = FoxAmber50,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else accent,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent else accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun EmptyInbox(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(text = subtitle, color = FoxNeutral60, fontSize = 12.sp)
    }
}

@Composable
private fun AlertCard(
    alert: FoxAlert,
    onAcknowledge: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = priorityColor(alert.priority)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !alert.acknowledged, onClick = onAcknowledge),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            // Priority spine — unread alerts keep a solid accent bar.
            Box(
                Modifier
                    .size(width = 3.dp, height = 72.dp)
                    .background(if (alert.acknowledged) accent.copy(alpha = 0.3f) else accent),
            )
            Column(Modifier.weight(1f).padding(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PriorityTag(alert.priority, accent)
                        if (alert.symbol != null) {
                            Text(
                                text = alert.symbol,
                                color = FoxNeutral60,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        text = formatTimestamp(alert.timestamp),
                        color = FoxNeutral60,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = alert.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (alert.acknowledged) FontWeight.Normal else FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = alert.body,
                    color = FoxNeutral60,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete alert",
                    tint = FoxNeutral60,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PriorityTag(priority: AlertPriority, accent: Color) {
    Text(
        text = priority.name,
        color = accent,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(accent.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private fun priorityColor(priority: AlertPriority): Color = when (priority) {
    AlertPriority.CRITICAL -> FoxError
    AlertPriority.HIGH -> FoxWarning
    AlertPriority.MEDIUM -> FoxAmber50
    AlertPriority.LOW -> FoxInfo
}

/** Relative for recent alerts (what a trader actually cares about), absolute beyond a day. */
private fun formatTimestamp(timestamp: Long): String {
    val deltaMs = System.currentTimeMillis() - timestamp
    return when {
        deltaMs < 60_000L -> "just now"
        deltaMs < 3_600_000L -> "${deltaMs / 60_000L}m ago"
        deltaMs < 86_400_000L -> "${deltaMs / 3_600_000L}h ago"
        else -> SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
