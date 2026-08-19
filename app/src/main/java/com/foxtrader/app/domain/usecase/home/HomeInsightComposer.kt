package com.foxtrader.app.domain.usecase.home

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.WorkspaceProfile

/** Epistemic label for anything the dashboard says out loud. */
enum class InsightKind { FACT, CALCULATION, ASSUMPTION, PROBABILITY, OPINION }

data class ClassifiedInsight(
    val kind: InsightKind,
    val text: String,
)

/**
 * Builds the Home AI summary from already-computed numbers.
 * Pure: no I/O, no forecasts presented as facts.
 */
object HomeInsightComposer {

    fun compose(
        results: List<ScreenerResult>,
        unreadAlerts: Int,
        openPositions: Int,
        profile: WorkspaceProfile,
        synthetic: Boolean,
    ): List<ClassifiedInsight> {
        val insights = mutableListOf<ClassifiedInsight>()

        if (synthetic) {
            insights += ClassifiedInsight(
                InsightKind.FACT,
                "Scan is labelled simulated — these moves are generated bars, not a live tape.",
            )
        }

        if (results.isEmpty()) {
            insights += ClassifiedInsight(
                InsightKind.FACT,
                "No scan results yet. Open Markets to run a watchlist scan.",
            )
            return insights
        }

        val bullish = results.count { it.direction == Direction.BULLISH }
        val bearish = results.count { it.direction == Direction.BEARISH }
        insights += ClassifiedInsight(
            InsightKind.FACT,
            "Scan covered ${results.size} symbols: $bullish scored as buys, $bearish as sells.",
        )

        val avgScore = results.map { it.score }.average()
        insights += ClassifiedInsight(
            InsightKind.CALCULATION,
            "Average composite score is ${avgScore.toInt()} / 100 across the visible universe.",
        )

        val breadth = if (results.isEmpty()) 0.0 else bullish.toDouble() / results.size
        insights += ClassifiedInsight(
            InsightKind.ASSUMPTION,
            when {
                breadth >= 0.65 -> "Breadth leans risk-on if the scan universe matches the market you trade."
                breadth <= 0.35 -> "Breadth leans risk-off if the scan universe matches the market you trade."
                else -> "Breadth is mixed — treat direction as symbol-specific, not a market call."
            },
        )

        val top = results.maxByOrNull { it.score }
        if (top != null) {
            insights += ClassifiedInsight(
                InsightKind.PROBABILITY,
                "${top.symbol} is the highest-ranked print (${top.score}). Rank is a historical composite, not a forecast.",
            )
        }

        if (openPositions > 0) {
            insights += ClassifiedInsight(
                InsightKind.FACT,
                "$openPositions open position${if (openPositions == 1) "" else "s"} feeding portfolio exposure.",
            )
        }

        if (unreadAlerts > 0) {
            insights += ClassifiedInsight(
                InsightKind.FACT,
                "$unreadAlerts unread alert${if (unreadAlerts == 1) "" else "s"} in the inbox.",
            )
        }

        insights += ClassifiedInsight(
            InsightKind.OPINION,
            "Workspace is tuned for ${profile.greetingFocus} work on ${profile.preferredTimeframe.label}. That is a preference, not an edge.",
        )

        return insights
    }
}
