package com.foxtrader.app.data.remote.dukascopy

import com.foxtrader.app.domain.model.Tick
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoder for Dukascopy `.bi5` tick data (already LZMA-decompressed).
 *
 * Dukascopy publishes one compressed file per instrument per hour. After LZMA
 * decompression the payload is a sequence of fixed 20-byte, BIG-ENDIAN records:
 *
 * | offset | type    | field                                          |
 * |--------|---------|------------------------------------------------|
 * | 0      | int32   | millisecond offset from the hour start         |
 * | 4      | int32   | ask price in points (integer)                  |
 * | 8      | int32   | bid price in points (integer)                  |
 * | 12     | float32 | ask volume                                     |
 * | 16     | float32 | bid volume                                     |
 *
 * Point prices are converted to real prices by dividing by a per-instrument
 * `pointValue` (e.g. 100_000 for 5-digit FX pairs, 1_000 for JPY pairs).
 *
 * This class only handles the binary decode step. Fetching over HTTP and LZMA
 * decompression are deliberately out of scope here (network/dependency gated).
 *
 * Pure Kotlin (java.nio only) — no Android dependencies, fully unit-testable.
 */
@Singleton
class DukascopyTickDecoder @Inject constructor() {

    /**
     * Decode a decompressed `.bi5` payload into ticks.
     *
     * @param decompressed The raw, LZMA-decompressed byte payload.
     * @param hourStartMs Epoch millis at the start of the hour the file covers.
     * @param pointValue Divisor converting integer points to a real price.
     * @return Decoded ticks in file order. A trailing partial record (< 20 bytes)
     *         is ignored. Empty input yields an empty list.
     */
    fun decode(decompressed: ByteArray, hourStartMs: Long, pointValue: Double): List<Tick> {
        if (decompressed.isEmpty()) return emptyList()

        val recordCount = decompressed.size / RECORD_SIZE
        if (recordCount == 0) return emptyList()

        val buffer = ByteBuffer.wrap(decompressed).order(ByteOrder.BIG_ENDIAN)
        val ticks = ArrayList<Tick>(recordCount)

        for (i in 0 until recordCount) {
            val base = i * RECORD_SIZE
            buffer.position(base)
            val msOffset = buffer.int
            val askPoints = buffer.int
            val bidPoints = buffer.int
            val askVolume = buffer.float
            val bidVolume = buffer.float

            ticks.add(
                Tick(
                    timestampMs = hourStartMs + msOffset,
                    bid = bidPoints / pointValue,
                    ask = askPoints / pointValue,
                    bidVolume = bidVolume.toDouble(),
                    askVolume = askVolume.toDouble(),
                )
            )
        }

        return ticks
    }

    private companion object {
        /** Size of a single Dukascopy tick record in bytes. */
        const val RECORD_SIZE = 20
    }
}
