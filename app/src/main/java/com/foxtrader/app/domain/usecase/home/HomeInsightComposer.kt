package com.foxtrader.app.domain.usecase.home

import com.foxtrader.app.domain.model.MarketMover
import com.foxtrader.app.domain.model.WorkspaceProfile
import kotlin.math.abs

/** Epistemic label for anything the dashboard says out loud. */
enum class InsightKind { FACT, CALCULATION, ASSUMPTION, PROBABILITY, OPINION }

data class ClassifiedInsight(
    val kind: InsightKind,
    val text: String,
)

/** Builds the Home summary from price-only watchlist snapshots. */
object HomeInsightComposer {

    fun compose(
        results: List<MarketMover>,
        unreadAlerts: Int,
        openPositions: Int,
        profile: WorkspaceProfile,
        synthetic: Boolean,
    ): List<ClassifiedInsight> {
        val insights = mutableListOf<ClassifiedInsight>()

        if (synthetic) {
            insights += ClassifiedInsight(
                InsightKind.FACT,
                "Market snapshot is labelled simulated — these moves are generated bars, not a live tape.",
            )
        }

        if (results.isEmpty()) {
            insights += ClassifiedInsight(
                InsightKind.FACT,
                "No market snapshot yet. Add symbols to the Watchlist and refresh when candle history is available.",
            )
            return insights
        }

        val positive = results.count { it.changePercent >= 0.0 }
        val negative = results.size - positive
        insights += ClassifiedInsight(
            InsightKind.FACT,
            "Watchlist snapshot covers ${results.size} symbols: $positive positive and $negative negative over the local lookback.",
        )

        val averageAbsoluteMove = results.map { abs(it.changePercent) }.average()
        insights += ClassifiedInsight(
            InsightKind.CALCULATION,
            "Average absolute watchlist move is ${"%.2f".format(java.util.Locale.US, averageAbsoluteMove)}%.",
        )

        val breadth = positive.toDouble() / results.size
        insights += ClassifiedInsight(
            InsightKind.ASSUMPTION,
            when {
                breadth >= 0.65 -> "Watchlist breadth leans risk-on if this list represents the markets you trade."
                breadth <= 0.35 -> "Watchlist breadth leans risk-off if this list represents the markets you trade."
                else -> "Watchlist breadth is mixed — price direction remains symbol-specific."
            },
        )

        val top = results.maxByOrNull { abs(it.changePercent) }
        if (top != null) {
            insights += ClassifiedInsight(
                InsightKind.CALCULATION,
                "${top.symbol} has the largest absolute move in this snapshot (${"%+.2f".format(java.util.Locale.US, top.changePercent)}%).",
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
