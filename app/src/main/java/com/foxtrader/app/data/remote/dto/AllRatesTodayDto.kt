package com.foxtrader.app.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AllRatesTodayCurrencyDto(
    val code: String,
    val name: String,
    val symbol: String = "",
)

/** Legacy FoxTrader-backend proxy response, retained for compatibility. */
@Serializable
data class AllRatesTodaySymbolsResponse(
    val provider: String,
    val currencies: List<AllRatesTodayCurrencyDto> = emptyList(),
    val pairs: List<String> = emptyList(),
    val currency_count: Int = currencies.size,
    val pair_count: Int = pairs.size,
)

/** Vendor-native /api/v1/symbols response used by the direct Android client. */
@Serializable
data class AllRatesTodayVendorSymbolsResponse(
    val currencies: List<AllRatesTodayCurrencyDto> = emptyList(),
    val count: Int = currencies.size,
)
