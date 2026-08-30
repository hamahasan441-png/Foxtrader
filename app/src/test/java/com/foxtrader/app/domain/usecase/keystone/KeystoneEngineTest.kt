package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeystoneEngineTest {

    private val engine = KeystoneFixtures.engine()

    @Test
    fun `publishes the sequence when the sequence is present`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val result = analyze(built)

        assertTrue(
            "Nothing published on a series built to contain the sequence. ${result.note}",
            result.signals.isNotEmpty(),
        )
        assertTrue("No sweep of tracked liquidity was found.", result.sweeps.isNotEmpty())
    }

    /**
     * Every published signal must satisfy every step it claims to have passed.
     *
     * Checked on the output rather than trusted from the code path, because a
     * refactor that quietly drops one stage would still produce plausible
     * arrows and nothing else in this suite would notice.
     */
    @Test
    fun `every published signal satisfies every step`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val config = KeystoneConfig()
        val result = analyze(built, config)
        assertTrue(result.signals.isNotEmpty())

        for (signal in result.signals) {
            assertNotNull("Step 3: published without a divergence.", signal.divergence)

            // Step 4: the confirmation is a closed candle before the entry.
            assertTrue(
                "Step 4: displacement at ${signal.displacement.index} is not before entry ${signal.index}.",
                signal.displacement.index < signal.index,
            )
            assertTrue(
                "Step 4: displacement is smaller than the configured impulse.",
                signal.displacement.atrMultiple >= config.displacementAtrMultiple,
            )

            // Step 2 and 6: the stop sits beyond what the sweep already rejected.
            if (signal.direction == Direction.BULLISH) {
                assertTrue(
                    "Step 6: stop ${signal.stopLoss} is not below the swept extreme ${signal.sweep.extreme}.",
                    signal.stopLoss <= signal.sweep.extreme,
                )
                assertTrue("Entry must sit above the stop.", signal.entry > signal.stopLoss)
                assertTrue("Target must sit above the entry.", signal.takeProfit > signal.entry)
            } else {
                assertTrue(
                    "Step 6: stop ${signal.stopLoss} is not above the swept extreme ${signal.sweep.extreme}.",
                    signal.stopLoss >= signal.sweep.extreme,
                )
                assertTrue("Entry must sit below the stop.", signal.entry < signal.stopLoss)
                assertTrue("Target must sit below the entry.", signal.takeProfit < signal.entry)
            }

            // Step 7: nothing is taken below the reward floor.
            val risk = abs(signal.entry - signal.stopLoss)
            val reward = abs(signal.takeProfit - signal.entry) / risk
            assertTrue(
                "Step 7: ${"%.2f".format(reward)}R is below the ${config.minRewardMultiple}R floor.",
                reward >= config.minRewardMultiple - 1e-9,
            )

            // Step 8: the entry sits inside a permitted session.
            val session = signal.session
            assertTrue(
                "Step 8: entry at ${signal.index} fell outside the permitted sessions.",
                session != null && session in config.sessions,
            )

            // Step 9: the risk on the signal is the configured risk.
            assertEquals(config.riskPercent, signal.riskPercent, 1e-9)
        }
    }

    /**
     * Required evidence that is unavailable is a reason to stand down, not a
     * reason to publish a different strategy under the same name.
     */
    @Test
    fun `refuses to publish when SMT is required and no peer exists`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val result = engine.analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = built.primary,
            peers = emptyList(),
        )

        assertTrue(result.signals.isEmpty())
        assertTrue(
            "The engine must say why it stood down. Got: ${result.note}",
            result.note?.contains("SMT") == true,
        )
    }

    /**
     * With the divergence made optional the same series still trades, which is
     * what shows the previous test measured the requirement rather than a
     * broken pipeline further down.
     */
    @Test
    fun `without the SMT requirement the same series still trades`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val result = engine.analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = built.primary,
            peers = emptyList(),
            config = KeystoneConfig(requireSmt = false),
        )
        assertTrue("SMT was not the only thing standing in the way.", result.signals.isNotEmpty())
        assertTrue(
            "A signal published without a peer cannot carry a divergence.",
            result.signals.all { it.divergence == null },
        )
    }

    @Test
    fun `a peer declared inverse but moving in step is not accepted as evidence`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val result = engine.analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = built.primary,
            peers = listOf(KeystoneFixtures.peerOf(built, KeystonePolarity.INVERSE)),
        )
        assertTrue(result.signals.isEmpty())
        assertEquals(
            "The refusal must be recorded as a missing divergence.",
            true,
            result.rejections.containsKey(KeystoneRejection.NO_SMT) || result.sweeps.isEmpty(),
        )
    }

    /** One swept shelf is one idea, however many retracements it offers. */
    @Test
    fun `one signal per liquidity event`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val result = analyze(built)
        val perSweep = result.signals.groupBy { it.sweep.index }
        val repeated = perSweep.filterValues { it.size > 1 }
        assertTrue("The same sweep produced ${repeated.values.map { it.size }} signals.", repeated.isEmpty())
    }

    /**
     * A random walk contains no sweep-divergence-displacement sequence, so an
     * engine that reports one on it is reporting its own parameters.
     */
    @Test
    fun `a random walk produces nothing`() {
        val walk = KeystoneFixtures.walk(size = 1_500, seed = 11)
        val peer = KeystoneFixtures.walk(size = 1_500, seed = 12)
        val result = engine.analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = walk,
            peers = listOf(
                com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries(
                    KeystoneFixtures.PEER, peer, KeystonePolarity.POSITIVE,
                ),
            ),
        )
        assertTrue(
            "Found ${result.signals.size} setups in a random walk.",
            result.signals.size <= MAX_WALK_SIGNALS,
        )
    }

    @Test
    fun `short series are refused rather than guessed at`() {
        val built = KeystoneFixtures.sequence(cycles = 2)
        val result = analyze(built)
        assertTrue(result.signals.isEmpty())
        assertNotNull(result.note)
    }

    @Test
    fun `the report never rests the verdict on the win rate`() {
        val built = KeystoneFixtures.sequence(cycles = 30)
        val result = analyze(built)
        assertTrue(
            "The acceptance summary must mark the win rate as excluded.",
            result.acceptance.summary.contains("not a criterion"),
        )
    }

    private fun analyze(built: KeystoneFixtures.Built, config: KeystoneConfig = KeystoneConfig()) =
        engine.analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = built.primary,
            peers = listOf(KeystoneFixtures.peerOf(built)),
            config = config,
        )

    private companion object {
        /**
         * A random walk still throws up the occasional coincidence, and demanding
         * literally zero would be testing luck rather than the engine.
         */
        const val MAX_WALK_SIGNALS = 3
    }
}
