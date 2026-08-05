package com.foxtrader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a validated LIT X institutional signal.
 *
 * LIT X analysis was previously ephemeral — a validated A/A+ setup vanished as
 * soon as the user left the screen. Persisting the accepted signals gives the
 * trader a reviewable history (what LIT X flagged, when, at what grade/R:R).
 *
 * Indexed on `createdAt` (history reads newest-first) and `symbol` (per-symbol
 * filtering). Purely additive — no existing table is touched.
 */
@Entity(
    tableName = "litx_signals",
    indices = [Index(value = ["createdAt"]), Index(value = ["symbol"])],
)
data class LitXSignalEntity(
    @PrimaryKey val id: String, // "$symbol:$timeframe:$createdAt"
    val symbol: String,
    val timeframe: String,      // Timeframe.label
    val direction: String,      // Direction.name
    val grade: String,          // LitXGrade.name
    val score: Int,             // 0..100 confidence
    val entry: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskReward: Double,
    val rationale: String,
    val createdAt: Long,
)
