package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.MtfContextProvider
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies Keystone with the correlated markets its divergence test needs.
 *
 * Keystone is the only engine here that cannot be computed from the charted
 * series alone, so this is the one place the chart has to reach for a second
 * symbol. Two things are kept together deliberately: which markets pair with
 * which — including the ones that pair *inversely* — and the provenance gate
 * that keeps generated bars out of a divergence test. Peer data that cannot be
 * trusted simply does not arrive, and the engine then stands down rather than
 * quietly dropping its own requirement.
 */
@Singleton
class KeystonePeerProvider @Inject constructor(
    private val context: MtfContextProvider,
) {

    suspend fun peersFor(
        symbol: String,
        timeframe: Timeframe,
        refreshMissing: Boolean = false,
    ): List<KeystonePeerSeries> {
        val table = KeystoneCorrelation.peersFor(symbol)
        if (table.isEmpty()) return emptyList()
        val candles = context.getPeerContext(table.map { it.symbol }, timeframe, refreshMissing)
        return table.mapNotNull { peer ->
            candles[peer.symbol]?.let { KeystonePeerSeries(peer.symbol, it, peer.polarity) }
        }
    }
}
