package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitEventType
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The inducement, and the chronology the three structural events must share.
 *
 * A live chart reported "sequence rejected — missing confirmed IDM" while the
 * level was plainly on screen, and the reason was a conflation: the search
 * required the swing *itself* to have formed inside the IDM-to-BOS window. That
 * window belongs to the **sweep**. A level that had been sitting there for
 * thirty bars — the ordinary case, and the more meaningful pool — could not be
 * an inducement at all.
 */
class LitInducementTest {

    private val detector = LitProStructureDetector()

    /** A reverting channel with real impulse candles, as a chart has. */
    private fun marketLike(size: Int, seed: Int): List<Candle> {
        val random = Random(seed)
        var price = 1.1000
        return (0 until size).map { index ->
            val pull = -(price - 1.1000) / 0.0060 * 0.00050
            val open = price
            val impulse = index % 23 == 0
            val close = if (impulse) {
                open + (if (pull >= 0) 1.0 else -1.0) * 0.0022
            } else {
                open + pull + (random.nextDouble() - 0.5) * 0.0009
            }
            price = close
            val wick = if (impulse) abs(close - open) * 0.05 else abs(close - open) * 0.6 + 0.00006
            Candle(
                timestamp = 1_700_000_000_000L + index * 900_000L,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0,
            )
        }
    }

    private fun contexts(seed: Int) = marketLike(3_000, seed).let { candles ->
        (700 until candles.size step 7).mapNotNull { end ->
            val prefix = candles.subList((end - 640 + 1).coerceAtLeast(0), end + 1)
            runCatching { detector.detect(prefix, LitConfig().sanitized()) }.getOrNull()
        }
    }

    @Test
    fun `an inducement is found on ordinary market structure`() {
        val found = contexts(seed = 1).count { it.inducement != null }
        assertTrue("no inducement was ever found on market-like structure", found > 0)
    }

    @Test
    fun `an inducement may rest on a level older than the sweep window`() {
        // The specific regression. If every inducement's origin sits within the
        // IDM-to-BOS window, the old conflation is back: the level is being
        // required to form in the same handful of bars that its sweep must
        // happen in.
        val config = LitConfig().sanitized()
        val older = contexts(seed = 2)
            .mapNotNull { it.inducement }
            .count { it.confirmationIndex - it.originIndex > config.maxIdmToBosBars }

        assertTrue(
            "every inducement level formed inside the sweep window, which is the bug this pins",
            older > 0,
        )
    }

    @Test
    fun `the three events are handed to the validator in a possible order`() {
        // The detector must not build a context the validator can only reject.
        // It previously anchored the inducement search to whatever broke most
        // recently while selecting a different BOS, so the sequence it produced
        // routinely had the IDM after the BOS.
        val validator = LitSequenceValidator()
        val config = LitConfig().sanitized()

        contexts(seed = 3).forEach { context ->
            val idm = context.inducement ?: return@forEach
            val bos = context.bos ?: return@forEach
            val choch = context.choch ?: return@forEach

            assertTrue("IDM is not an IDM", idm.type == LitEventType.IDM)
            assertTrue(
                "the detector produced an IDM confirmed after its own BOS",
                idm.confirmationIndex < bos.confirmationIndex,
            )
            assertTrue(
                "the continuation break must run against the shift that follows it",
                bos.direction != choch.direction,
            )
            // Whatever the validator decides, it must never be for one of the
            // chronology reasons the detector itself controls.
            val reason = validator.validate(context, config).reason
            assertTrue(
                "the detector handed the validator an impossible sequence: $reason",
                !reason.contains("must be confirmed before") &&
                    !reason.contains("must be opposite"),
            )
        }
    }
}
