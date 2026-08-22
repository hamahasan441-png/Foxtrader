package com.foxtrader.app.domain.model

/** A confirmed swing point (fractal high or low). */
data class SwingPoint(
    val type: SwingType,
    val price: Double,
    val timestamp: Long,
    val index: Int,
)

enum class SwingType { HIGH, LOW }

/** Type of market-structure break. */
enum class StructureBreakType { BOS, CHOCH, MSS, IDM }

/**
 * A break of market structure — BOS (continuation), CHOCH (reversal),
 * MSS (strong shift), or IDM (inducement).
 *
 * [labelOverride] is presentation metadata for confirmed domain-derived events
 * that reuse the structure renderer (for example LiT Pro Pullback / POI / SCOB).
 * It defaults to null so the core market-structure engine and serialized/replay
 * semantics remain unchanged.
 */
data class StructureBreak(
    val type: StructureBreakType,
    val direction: Direction,
    val breakPrice: Double,
    val breakTimestamp: Long,
    val breakIndex: Int,
    val confirmed: Boolean,
    val labelOverride: String? = null,
)

/** Aggregate market-structure state for a series of candles. */
data class MarketStructure(
    val bias: Bias,
    val swingHighs: List<SwingPoint>,
    val swingLows: List<SwingPoint>,
    val breaks: List<StructureBreak>,
)
