package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.VolumeProfile
import com.foxtrader.app.domain.usecase.smc.SmcDetector

/**
 * Backward-compatible chart adapter while SmcDetector exposes the newer
 * buildVolumeProfile implementation internally. Keeping this call surface
 * stable avoids breaking the chart computation pipeline during the SMC
 * migration.
 */
internal fun SmcDetector.computeVolumeProfile(
    candles: List<Candle>,
    buckets: Int = 24,
): VolumeProfile = buildVolumeProfile(candles, buckets)
