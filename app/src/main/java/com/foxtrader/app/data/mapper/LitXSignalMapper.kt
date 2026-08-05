package com.foxtrader.app.data.mapper

import com.foxtrader.app.data.local.entity.LitXSignalEntity
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXSignalRecord
import com.foxtrader.app.domain.model.Timeframe

/** Entity → domain. Enum/timeframe strings are parsed back to their types. */
fun LitXSignalEntity.toRecord(): LitXSignalRecord = LitXSignalRecord(
    id = id,
    symbol = symbol,
    timeframe = Timeframe.fromLabel(timeframe),
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.BULLISH),
    grade = runCatching { LitXGrade.valueOf(grade) }.getOrDefault(LitXGrade.B),
    score = score,
    entry = entry,
    stopLoss = stopLoss,
    takeProfit1 = takeProfit1,
    takeProfit2 = takeProfit2,
    riskReward = riskReward,
    rationale = rationale,
    createdAt = createdAt,
)

/** Domain → entity. */
fun LitXSignalRecord.toEntity(): LitXSignalEntity = LitXSignalEntity(
    id = id,
    symbol = symbol,
    timeframe = timeframe.label,
    direction = direction.name,
    grade = grade.name,
    score = score,
    entry = entry,
    stopLoss = stopLoss,
    takeProfit1 = takeProfit1,
    takeProfit2 = takeProfit2,
    riskReward = riskReward,
    rationale = rationale,
    createdAt = createdAt,
)
