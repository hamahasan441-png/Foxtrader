package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.Candle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoder for Dukascopy's native candle `.bi5` files (already LZMA-decompressed).
 *
 * One record is 24 bytes, big-endian:
 * int32 seconds from the file's bucket start, int32 open, int32 close,
 * int32 low, int32 high, float32 volume.
 * Prices are integer points and must be divided by the instrument point value.
 * Volume is stored in 1e-7 units.
 *
 * Bucket start is 00:00 UTC of the day (M1), the first of the month (H1),
 * or 1 January (D1) — the same instant the archive path names.
 */
@Singleton
class DukascopyBi5CandleDecoder @Inject constructor() {

    fun decode(
        decompressed: ByteArray,
        bucketStartMs: Long,
        pointValue: Double,
    ): List<Candle> {
        if (decompressed.size < RECORD_BYTES || !pointValue.isFinite() || pointValue <= 0.0) {
            return emptyList()
        }
        val count = decompressed.size / RECORD_BYTES
        val buffer = ByteBuffer.wrap(decompressed).order(ByteOrder.BIG_ENDIAN)
        val candles = ArrayList<Candle>(count)
        for (index in 0 until count) {
            buffer.position(index * RECORD_BYTES)
            val shiftSeconds = buffer.int
            val openPoints = buffer.int
            val closePoints = buffer.int
            val lowPoints = buffer.int
            val highPoints = buffer.int
            val rawVolume = buffer.float
            if (shiftSeconds < 0 || openPoints <= 0 || closePoints <= 0 || lowPoints <= 0 || highPoints <= 0) {
                continue
            }
            if (highPoints < lowPoints || openPoints !in lowPoints..highPoints || closePoints !in lowPoints..highPoints) {
                continue
            }
            val volume = if (rawVolume.isFinite() && rawVolume >= 0f) rawVolume.toDouble() * VOLUME_SCALE else 0.0
            val candle = Candle(
                timestamp = bucketStartMs + shiftSeconds * 1_000L,
                open = openPoints / pointValue,
                high = highPoints / pointValue,
                low = lowPoints / pointValue,
                close = closePoints / pointValue,
                volume = volume,
            )
            if (candle.open.isFinite() && candle.high.isFinite() && candle.low.isFinite() && candle.close.isFinite()) {
                candles += candle
            }
        }
        return candles
    }

    private companion object {
        const val RECORD_BYTES = 24
        /** Dukascopy stores native-candle volume as lots × 10^-7. */
        const val VOLUME_SCALE = 10_000_000.0
    }
}
