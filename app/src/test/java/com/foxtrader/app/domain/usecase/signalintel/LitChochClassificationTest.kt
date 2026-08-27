package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitBreakMode
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.LitEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A change of character is the event the whole LiT sequence hangs off, so a
 * reversal that cannot be recognised as one makes the study silent no matter
 * what price does.
 *
 * Whether a break is a *change* of character or a continuation depends entirely
 * on the trend it broke. The classifier used to begin each pass with no trend
 * at all, which made the first break inside the lookback window unconditionally
 * a continuation — a market that had risen for a hundred bars and then broke
 * down was read as "the trend continues", because as far as that function was
 * concerned no trend had ever existed.
 *
 * These cases pin the behaviour end to end: the recent bars are identical and
 * only the history before them differs, and the same downward break must read
 * as a reversal after a rise and as continuation after a fall.
 *
 * Worth being precise about what this does and does not prove. It exercises the
 * classifier as a whole, and on this fixture the two readings differ partly
 * because the differing history also leaves differing levels behind, not only
 * because of the trend seed. Isolating the seed alone would need a prior trend
 * that leaves no level the recent bars can reach, and every attempt at building
 * one left structure of its own. So the seeding change is reasoned rather than
 * separately measured; what is measured is that the two markets are read
 * differently, which is the behaviour that matters to a trader.
 */
class LitChochClassificationTest {

    private val detector = LitProStructureDetector()

    private val config = LitConfig(
        setupLookback = 80,
        swingLeftBars = 2,
        swingRightBars = 2,
        breakMode = LitBreakMode.BODY,
    ).sanitized()

    private fun bar(index: Int, open: Double, close: Double, wick: Double = 0.05) = Candle(
        timestamp = 1_700_000_000_000L + index * 300_000L,
        open = open,
        high = maxOf(open, close) + wick,
        low = minOf(open, close) - wick,
        close = close,
        volume = 1_000.0,
    )

    /** Emit [bars] candles walking from the current price to [to]. */
    private fun MutableList<Candle>.ramp(to: Double, bars: Int): Double {
        val from = if (isEmpty()) 100.0 else last().close
        val step = (to - from) / bars
        var price = from
        repeat(bars) {
            val close = price + step
            add(bar(size, price, close))
            price = close
        }
        return price
    }

    /** A zigzag that drifts, leaving readable higher (or lower) pivots behind. */
    private fun MutableList<Candle>.zigzag(cycles: Int, start: Double, drift: Double) {
        var peak = start
        var trough = start - 2.0
        repeat(cycles) {
            ramp(peak, 3)
            ramp(trough, 3)
            peak += drift
            trough += drift
        }
    }

    /**
     * A quiet stretch that produces no pivots and breaks nothing, so the
     * actionable window contains only the tail below it.
     */
    private fun MutableList<Candle>.quiet(bars: Int) {
        val price = last().close
        repeat(bars) { add(bar(size, price, price)) }
    }

    /**
     * The identical recent structure used by both cases: a high, a low, a lower
     * high, then a break back below that low.
     */
    private fun MutableList<Candle>.tailWithDownwardBreak() {
        ramp(103.0, 6)
        ramp(100.5, 6)
        ramp(102.0, 6)
        ramp(100.2, 8)
    }

    private fun afterUptrend(): List<Candle> = mutableListOf<Candle>().apply {
        // Rising pivots: higher highs and higher lows, ending at 100.
        zigzag(cycles = 12, start = 90.0, drift = 1.0)
        ramp(100.0, 4)
        quiet(30)
        tailWithDownwardBreak()
    }

    private fun afterDowntrend(): List<Candle> = mutableListOf<Candle>().apply {
        // Falling pivots: lower highs and lower lows, ending at the same 100.
        zigzag(cycles = 12, start = 114.0, drift = -1.0)
        ramp(100.0, 4)
        quiet(30)
        tailWithDownwardBreak()
    }

    // Rounded: the two paths reach the same prices by different arithmetic, so
    // the last bits of floating-point dust differ and mean nothing here.
    private fun recentBarsOf(candles: List<Candle>) = candles.takeLast(26).map {
        "%.6f/%.6f".format(it.open, it.close)
    }

    @Test
    fun `the two cases really do share their recent structure`() {
        // Guards the experiment itself. If the tails differed, a difference in
        // classification would prove nothing about the seed.
        assertEquals(recentBarsOf(afterUptrend()), recentBarsOf(afterDowntrend()))
    }

    @Test
    fun `a downward break after an uptrend is a change of character`() {
        val context = detector.detect(afterUptrend(), config)

        assertNotNull(
            "the break that ended the rise must be recognised as a change of character",
            context.choch,
        )
        assertEquals(Direction.BEARISH, context.choch!!.direction)
        assertEquals(LitEventType.CHOCH, context.choch!!.type)
    }

    @Test
    fun `the same break after a downtrend is continuation`() {
        val context = detector.detect(afterDowntrend(), config)

        assertNotNull("the same bars must still break structure", context.bos)
        assertEquals(
            "a break that continues the prevailing trend is not a change of character",
            Direction.BEARISH,
            context.bos!!.direction,
        )
    }

    @Test
    fun `the prevailing trend is what decides the label`() {
        // The whole point, stated as one comparison: same bars, opposite
        // histories, different reading.
        val afterRise = detector.detect(afterUptrend(), config)
        val afterFall = detector.detect(afterDowntrend(), config)

        assertNotNull("a reversal must be seen after a rise", afterRise.choch)
        assertEquals(
            "the identical break must not read as a reversal after a fall",
            null,
            afterFall.choch,
        )
    }
}
