package com.foxtrader.app.data.remote.dukascopy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Pure Kotlin LZMA1 Decompressor.
 *
 * Implements the standard LZMA1 / LZMA-Alone decompressor (7-Zip LZMA SDK specification)
 * with zero external C/JNI/native dependencies.
 *
 * Used to decompress Dukascopy `.bi5` hourly tick archives downloaded over HTTP.
 *
 * Dukascopy `.bi5` layout:
 * - Bytes 0..4: LZMA properties (byte 0) + dictionary size (bytes 1..4, little-endian)
 * - Bytes 5..12: Uncompressed size (64-bit int, little-endian; -1 if unknown)
 * - Bytes 13..end: Range-coded compressed stream
 */
@Singleton
class LzmaDecompressor @Inject constructor() {

    /**
     * Decompress an LZMA-compressed byte array (e.g. Dukascopy `.bi5` file).
     *
     * @param compressed The compressed bytes containing standard LZMA header or raw payload.
     * @return Decompressed raw byte array, or empty array if input is empty or invalid.
     */
    fun decompress(compressed: ByteArray): ByteArray {
        if (compressed.size < HEADER_SIZE) return ByteArray(0)

        return try {
            val propByte = compressed[0].toInt() and 0xFF
            val lc = propByte % 9
            val rem = propByte / 9
            val lp = rem % 5
            val pb = rem / 5

            val buffer = ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(1)
            var dictSize = buffer.int
            if (dictSize < (1 shl 12)) {
                dictSize = 1 shl 12
            }
            val uncompressedSize = buffer.long

            val decoder = LzmaStreamDecoder(
                data = compressed,
                headerOffset = HEADER_SIZE,
                lc = lc,
                lp = lp,
                pb = pb,
                dictSize = dictSize,
                uncompressedSize = uncompressedSize,
            )
            decoder.decode()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private class LzmaStreamDecoder(
        private val data: ByteArray,
        private val headerOffset: Int,
        private val lc: Int,
        private val lp: Int,
        private val pb: Int,
        private val dictSize: Int,
        private val uncompressedSize: Long,
    ) {
        private var dataPos = headerOffset
        private var code: Long = 0L
        private var range: Long = 0xFFFFFFFFL

        private val isMatch = IntArray(NUM_STATES shl 4) { PROB_INIT }
        private val isRep = IntArray(NUM_STATES) { PROB_INIT }
        private val isRepG0 = IntArray(NUM_STATES) { PROB_INIT }
        private val isRepG1 = IntArray(NUM_STATES) { PROB_INIT }
        private val isRepG2 = IntArray(NUM_STATES) { PROB_INIT }
        private val isRep0Long = IntArray(NUM_STATES shl 4) { PROB_INIT }
        private val posSlotDecoder = IntArray(4 shl 6) { PROB_INIT }
        private val posDecoders = IntArray(NUM_FULL_DISTANCES - NUM_END_POS_STATES + 1) { PROB_INIT }
        private val alignDecoder = IntArray(16) { PROB_INIT }

        private val lenChoice1 = IntArray(1) { PROB_INIT }
        private val lenChoice2 = IntArray(1) { PROB_INIT }
        private val lenLow = IntArray(16 shl 3) { PROB_INIT }
        private val lenMid = IntArray(16 shl 3) { PROB_INIT }
        private val lenHigh = IntArray(256) { PROB_INIT }

        private val repLenChoice1 = IntArray(1) { PROB_INIT }
        private val repLenChoice2 = IntArray(1) { PROB_INIT }
        private val repLenLow = IntArray(16 shl 3) { PROB_INIT }
        private val repLenMid = IntArray(16 shl 3) { PROB_INIT }
        private val repLenHigh = IntArray(256) { PROB_INIT }

        private val litProbs = IntArray(0x300 shl (lc + lp)) { PROB_INIT }

        init {
            for (i in 0 until 5) {
                code = ((code shl 8) or nextByte().toLong()) and 0xFFFFFFFFL
            }
        }

        private fun nextByte(): Int {
            return if (dataPos < data.size) {
                data[dataPos++].toInt() and 0xFF
            } else {
                0
            }
        }

        private fun decodeBit(probs: IntArray, index: Int): Int {
            val prob = probs[index]
            val bound = (range ushr 11) * prob
            return if (code < bound) {
                range = bound
                probs[index] = prob + ((PROB_INIT * 2 - prob) ushr 5)
                if (range < 0x01000000L) {
                    range = (range shl 8) and 0xFFFFFFFFL
                    code = ((code shl 8) or nextByte().toLong()) and 0xFFFFFFFFL
                }
                0
            } else {
                range = (range - bound) and 0xFFFFFFFFL
                code = (code - bound) and 0xFFFFFFFFL
                probs[index] = prob - (prob ushr 5)
                if (range < 0x01000000L) {
                    range = (range shl 8) and 0xFFFFFFFFL
                    code = ((code shl 8) or nextByte().toLong()) and 0xFFFFFFFFL
                }
                1
            }
        }

        private fun decodeDirect(numBits: Int): Int {
            var res = 0
            for (i in 0 until numBits) {
                range = range ushr 1
                val t = (code - range) and 0xFFFFFFFFL
                if ((t ushr 31) == 0L) {
                    code = t
                    res = (res shl 1) or 1
                } else {
                    res = res shl 1
                }
                if (range < 0x01000000L) {
                    range = (range shl 8) and 0xFFFFFFFFL
                    code = ((code shl 8) or nextByte().toLong()) and 0xFFFFFFFFL
                }
            }
            return res
        }

        private fun decodeTree(probs: IntArray, baseIdx: Int, numBits: Int): Int {
            var m = 1
            for (i in 0 until numBits) {
                val b = decodeBit(probs, baseIdx + m)
                m = (m shl 1) + b
            }
            return m - (1 shl numBits)
        }

        private fun decodeRevTree(probs: IntArray, baseIdx: Int, numBits: Int): Int {
            var m = 1
            var res = 0
            for (i in 0 until numBits) {
                val b = decodeBit(probs, baseIdx + m)
                m = (m shl 1) + b
                res = res or (b shl i)
            }
            return res
        }

        private fun decodeLength(choice1: IntArray, choice2: IntArray, low: IntArray, mid: IntArray, high: IntArray, posState: Int): Int {
            if (decodeBit(choice1, 0) == 0) {
                return decodeTree(low, posState shl 3, 3)
            }
            if (decodeBit(choice2, 0) == 0) {
                return 8 + decodeTree(mid, posState shl 3, 3)
            }
            return 16 + decodeTree(high, 0, 8)
        }

        fun decode(): ByteArray {
            val out = ByteArrayOutputStream(if (uncompressedSize in 0..10_000_000) uncompressedSize.toInt() else 65536)
            var outSize = 0
            var state = 0
            var rep0 = 0
            var rep1 = 0
            var rep2 = 0
            var rep3 = 0
            val posMask = (1 shl pb) - 1

            // Fast circular dictionary buffer for match lookback
            val dictCapacity = min(maxOf(dictSize, 65536), 16 * 1024 * 1024)
            val dict = ByteArray(dictCapacity)
            var dictPos = 0

            fun appendByte(b: Byte) {
                out.write(b.toInt() and 0xFF)
                dict[dictPos] = b
                dictPos = (dictPos + 1) % dictCapacity
                outSize++
            }

            fun getByteFromDict(dist: Int): Byte {
                val p = (dictPos - 1 - dist + dictCapacity * (dist / dictCapacity + 1)) % dictCapacity
                return dict[p]
            }

            while (uncompressedSize < 0 || outSize < uncompressedSize) {
                val posState = outSize and posMask
                val stateIdx = (state shl 4) + posState

                if (decodeBit(isMatch, stateIdx) == 0) {
                    val prevByte = if (outSize > 0) getByteFromDict(0).toInt() and 0xFF else 0
                    val litState = ((outSize and ((1 shl lp) - 1)) shl lc) + (prevByte ushr (8 - lc))
                    val litBase = 0x300 * litState

                    val symbol = if (state < 7) {
                        var sym = 1
                        for (i in 0 until 8) {
                            val bit = decodeBit(litProbs, litBase + sym)
                            sym = (sym shl 1) or bit
                        }
                        sym and 0xFF
                    } else {
                        var matchByte = (getByteFromDict(rep0).toInt() and 0xFF)
                        var sym = 1
                        var matched = true
                        for (i in 0 until 8) {
                            val matchBit = (matchByte ushr 7) and 1
                            matchByte = matchByte shl 1
                            val probIdx = if (matched) {
                                litBase + 0x100 + (matchBit shl 8) + sym
                            } else {
                                litBase + sym
                            }
                            val bit = decodeBit(litProbs, probIdx)
                            sym = (sym shl 1) or bit
                            if (matchBit != bit) {
                                matched = false
                            }
                        }
                        sym and 0xFF
                    }

                    appendByte(symbol.toByte())
                    state = if (state < 4) 0 else if (state < 10) state - 3 else state - 6
                } else {
                    var length: Int
                    if (decodeBit(isRep, state) == 1) {
                        if (decodeBit(isRepG0, state) == 0) {
                            if (decodeBit(isRep0Long, stateIdx) == 0) {
                                state = if (state < 7) 9 else 11
                                appendByte(getByteFromDict(rep0))
                                continue
                            }
                        } else {
                            val dist = if (decodeBit(isRepG1, state) == 0) {
                                rep1
                            } else {
                                val d = if (decodeBit(isRepG2, state) == 0) rep2 else rep3
                                if (d == rep3) {
                                    rep3 = rep2
                                }
                                rep2 = rep1
                                d
                            }
                            rep1 = rep0
                            rep0 = dist
                        }
                        length = decodeLength(repLenChoice1, repLenChoice2, repLenLow, repLenMid, repLenHigh, posState) + 2
                        state = if (state < 7) 8 else 11
                    } else {
                        rep3 = rep2
                        rep2 = rep1
                        rep1 = rep0
                        state = if (state < 7) 7 else 10
                        length = decodeLength(lenChoice1, lenChoice2, lenLow, lenMid, lenHigh, posState) + 2
                        val posSlot = decodeTree(posSlotDecoder, min(length - 2, 3) shl 6, 6)
                        if (posSlot >= 4) {
                            val numDirectBits = (posSlot ushr 1) - 1
                            rep0 = (2 or (posSlot and 1)) shl numDirectBits
                            if (posSlot < 14) {
                                rep0 += decodeRevTree(posDecoders, rep0 - posSlot - 1, numDirectBits)
                            } else {
                                rep0 += decodeDirect(numDirectBits - 4) shl 4
                                rep0 += decodeRevTree(alignDecoder, 0, 4)
                                if (rep0 < 0 || rep0.toLong() == 0xFFFFFFFFL) {
                                    break // End of stream marker
                                }
                            }
                        } else {
                            rep0 = posSlot
                        }
                    }

                    if (rep0 >= outSize) {
                        break // Corrupt data or end
                    }

                    for (i in 0 until length) {
                        appendByte(getByteFromDict(rep0))
                        if (uncompressedSize >= 0 && outSize.toLong() == uncompressedSize) {
                            break
                        }
                    }
                }
            }

            return out.toByteArray()
        }
    }

    private companion object {
        const val HEADER_SIZE = 13
        const val PROB_INIT = 1024
        const val NUM_STATES = 12
        const val NUM_FULL_DISTANCES = 128
        const val NUM_END_POS_STATES = 14
    }
}
