package com.foxtrader.app.feature.tradepro.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.tradepro.CategoryPerformance
import com.foxtrader.app.domain.model.tradepro.CoachingInsight
import com.foxtrader.app.domain.model.tradepro.EmotionPerformance
import com.foxtrader.app.domain.model.tradepro.InsightSeverity
import com.foxtrader.app.domain.model.tradepro.RatingCalibration
import com.foxtrader.app.domain.model.tradepro.SessionPerformance
import com.foxtrader.app.domain.model.tradepro.TraderProfile
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral15
import com.foxtrader.app.ui.theme.FoxNeutral60
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraderProfileScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: TraderProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trader Profile", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = FoxAmber50)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            val profile = state.profile
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FoxAmber50) }

                state.error != null -> LabCard {
                    Text(state.error ?: "Something went wrong.", color = FoxBearishText, fontSize = 13.sp)
                }

                profile == null || !state.hasProfile -> LabCard {
                    SectionTitle("Coaching Profile")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        profile?.headline ?: "Complete managed trades to unlock performance analytics.",
                        color = FoxNeutral60,
                        fontSize = 13.sp,
                    )
                }

                else -> ProfileContent(profile)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileContent(profile: TraderProfile) {
    ArchetypeCard(profile)
    ScoreCards(profile)
    if (profile.edges.isNotEmpty()) {
        InsightSection("Your Edges", profile.edges)
    }
    if (profile.leaks.isNotEmpty()) {
        InsightSection("Your Leaks", profile.leaks)
    }
    SetupPerformanceCard(profile.setupPerformance)
    EmotionPerformanceCard(profile.emotionPerformance)
    SessionPerformanceCard(profile.sessionPerformance)
    if (profile.dayOfWeekPerformance.isNotEmpty()) {
        CategoryCard("Day of Week", profile.dayOfWeekPerformance)
    }
    if (profile.holdTimeBuckets.isNotEmpty()) {
        CategoryCard("Hold Time", profile.holdTimeBuckets)
    }
    RatingCalibrationCard(profile.ratingCalibration)
}

@Composable
private fun ArchetypeCard(profile: TraderProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxAmber50.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("YOUR ARCHETYPE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = FoxNeutral60)
            Spacer(Modifier.height(4.dp))
            Text(
                profile.archetype.label,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
            Spacer(Modifier.height(6.dp))
            Text(profile.archetype.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(profile.headline, fontSize = 11.sp, color = FoxNeutral60)
        }
    }
}

@Composable
private fun ScoreCards(profile: TraderProfile) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        ScoreGauge("Discipline", profile.disciplineScore, Modifier.weight(1f))
        ScoreGauge("Consistency", profile.consistencyScore, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreGauge(label: String, score: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, color = FoxNeutral60)
            Spacer(Modifier.height(8.dp))
            Text(
                "$score",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = scoreColor(score),
            )
            Text("/ 100", fontSize = 10.sp, color = FoxNeutral60)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(FoxNeutral15),
            ) {
                val fraction = (score / 100f).coerceIn(0f, 1f)
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(scoreColor(score)),
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightSection(title: String, insights: List<CoachingInsight>) {
    LabCard {
        SectionTitle(title)
        Spacer(Modifier.height(8.dp))
        insights.forEachIndexed { index, insight ->
            InsightRow(insight)
            if (index < insights.size - 1) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun InsightRow(insight: CoachingInsight) {
    val accent = severityColor(insight.severity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(insight.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(3.dp))
            Text(insight.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(insight.metric, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = FoxNeutral60)
        }
    }
}

@Composable
private fun SetupPerformanceCard(setups: List<CategoryPerformance>) {
    if (setups.isEmpty()) return
    LabCard {
        SectionTitle("Setup Performance")
        Spacer(Modifier.height(8.dp))
        setups.take(8).forEach { PerfRow(it.category, it.trades, it.winRate, it.netPnl) }
    }
}

@Composable
private fun CategoryCard(title: String, categories: List<CategoryPerformance>) {
    LabCard {
        SectionTitle(title)
        Spacer(Modifier.height(8.dp))
        categories.forEach { PerfRow(it.category, it.trades, it.winRate, it.netPnl) }
    }
}

@Composable
private fun EmotionPerformanceCard(emotions: List<EmotionPerformance>) {
    if (emotions.isEmpty()) return
    LabCard {
        SectionTitle("Emotional State")
        Spacer(Modifier.height(8.dp))
        emotions.forEach {
            PerfRow(
                it.emotion.name.lowercase(Locale.US).replaceFirstChar { c -> c.uppercase() },
                it.trades,
                it.winRate,
                it.netPnl,
            )
        }
    }
}

@Composable
private fun SessionPerformanceCard(sessions: List<SessionPerformance>) {
    if (sessions.isEmpty()) return
    LabCard {
        SectionTitle("Session Performance")
        Spacer(Modifier.height(8.dp))
        sessions.forEach { PerfRow(it.session.label, it.trades, it.winRate, it.netPnl) }
    }
}

@Composable
private fun PerfRow(label: String, trades: Int, winRate: Double, netPnl: Double) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text("$trades trades \u00B7 ${pct(winRate)} win", fontSize = 10.sp, color = FoxNeutral60)
        }
        Text(
            fmtMoney(netPnl),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = pnlColor(netPnl),
        )
    }
}

@Composable
private fun RatingCalibrationCard(calibration: RatingCalibration) {
    LabCard {
        SectionTitle("Self-Rating Calibration")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Avg on Wins", fmt(calibration.avgRatingOnWins), FoxBullishText, Modifier.weight(1f))
            MetricTile("Avg on Losses", fmt(calibration.avgRatingOnLosses), FoxBearishText, Modifier.weight(1f))
            MetricTile(
                "Correlation",
                fmt(calibration.correlation),
                if (calibration.isWellCalibrated) FoxBullishText else FoxAmber50,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (calibration.isWellCalibrated) {
                "Well calibrated \u2014 your higher-rated trades genuinely perform better. Trust your read."
            } else {
                "Weak calibration \u2014 your self-ratings don't yet predict outcomes. Rate trades on process, not hope."
            },
            fontSize = 11.sp,
            color = FoxNeutral60,
        )
        if (calibration.byRating.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            calibration.byRating.forEach { PerfRow(it.category, it.trades, it.winRate, it.netPnl) }
        }
    }
}

// --- Shared private composables ---

@Composable
private fun LabCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = FoxAmber50)
}

@Composable
private fun MetricTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = FoxNeutral15),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 11.sp, color = FoxNeutral60)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> FoxNeutral60
}

private fun scoreColor(score: Int): Color = when {
    score >= 70 -> FoxBullishText
    score >= 50 -> FoxAmber50
    else -> FoxBearishText
}

private fun severityColor(severity: InsightSeverity): Color = when (severity) {
    InsightSeverity.POSITIVE -> FoxBullishText
    InsightSeverity.INFO -> FoxNeutral60
    InsightSeverity.WARNING -> FoxAmber50
    InsightSeverity.CRITICAL -> FoxBearishText
}

private fun fmt(v: Double): String = if (v.isFinite()) String.format(Locale.US, "%.2f", v) else "--"
private fun fmtMoney(v: Double): String = String.format(Locale.US, "%+.2f", v)
private fun pct(v: Double): String = String.format(Locale.US, "%.0f%%", v * 100)
