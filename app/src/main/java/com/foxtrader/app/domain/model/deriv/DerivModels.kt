package com.foxtrader.app.domain.model.deriv

enum class DerivAccountType { DEMO, REAL, UNKNOWN }

enum class DerivConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

data class DerivCredentials(
    val appId: String,
    val authToken: String,
)

data class DerivAccount(
    val accountId: String,
    val accountType: DerivAccountType,
    val currency: String,
    val balance: Double?,
    val group: String?,
    val status: String?,
)

data class DerivOtpSession(
    val webSocketUrl: String,
    val accountId: String,
    val accountType: DerivAccountType,
)

data class DerivActiveSymbol(
    val symbol: String,
    val displayName: String,
    val market: String?,
    val subgroup: String?,
    val submarket: String?,
    val symbolType: String?,
    val pipSize: Double?,
    val exchangeOpen: Boolean,
    val tradingSuspended: Boolean,
)

data class DerivTick(
    val symbol: String,
    val quote: Double,
    val epochSeconds: Long,
    val pipSize: Int?,
)

data class DerivBalance(
    val amount: Double,
    val currency: String,
)

data class DerivPosition(
    val contractId: Long,
    val contractType: String,
    val symbol: String?,
    val currency: String,
    val buyPrice: Double?,
    val bidPrice: Double?,
    val payout: Double?,
    val profit: Double?,
    val isSold: Boolean,
)

data class DerivProposalRequest(
    val underlyingSymbol: String,
    val amount: Double,
    val basis: String = "stake",
    val contractType: String,
    val currency: String = "USD",
    val duration: Int? = null,
    val durationUnit: String? = null,
    val multiplier: Int? = null,
    val barrier: String? = null,
    val barrier2: String? = null,
) {
    init {
        require(underlyingSymbol.isNotBlank())
        require(amount > 0.0 && amount.isFinite())
        require(contractType.isNotBlank())
        require(currency.isNotBlank())
        require(duration == null || duration > 0)
        require(multiplier == null || multiplier > 0)
    }
}

data class DerivProposal(
    val id: String,
    val askPrice: Double?,
    val payout: Double?,
    val spot: Double?,
    val longcode: String?,
)

data class DerivBuyResult(
    val contractId: Long,
    val transactionId: Long?,
    val buyPrice: Double?,
    val balanceAfter: Double?,
    val payout: Double?,
    val longcode: String?,
)

data class DerivSellResult(
    val contractId: Long,
    val transactionId: Long?,
    val soldFor: Double?,
    val balanceAfter: Double?,
)

/**
 * Hard boundary used by Native Deriv trading: automatic real-money execution
 * is never eligible. REAL requires an explicit, fresh user confirmation.
 */
data class DerivExecutionAuthorization(
    /** Exact account reviewed by the user. Prevents cross-account confirmation reuse. */
    val accountId: String,
    val accountType: DerivAccountType,
    val userConfirmed: Boolean,
    /** Timestamp captured at the REVIEW step, not at submission time. */
    val confirmationEpochMs: Long,
    val maxConfirmationAgeMs: Long = 30_000L,
) {
    init {
        require(accountId.isNotBlank()) { "Authorization account ID is required" }
        require(maxConfirmationAgeMs > 0L) { "Confirmation age must be positive" }
    }

    /**
     * Validates against the repository's currently authenticated account and
     * repository-owned wall clock. Callers cannot spoof `now` to refresh an old
     * REAL-money confirmation. Demo actions still require account identity to
     * match, but do not require the REAL-money manual-confirmation gate.
     */
    fun canSubmitFor(account: DerivAccount, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (account.accountId != accountId || account.accountType != accountType) return false
        if (accountType == DerivAccountType.UNKNOWN) return false
        if (accountType == DerivAccountType.DEMO) return true
        if (!userConfirmed || confirmationEpochMs <= 0L) return false
        val age = nowEpochMs - confirmationEpochMs
        return age in 0..maxConfirmationAgeMs
    }
}

data class DerivContractSpec(
    val contractType: String,
    val category: String?,
    val expiryType: String?,
    val market: String?,
    val submarket: String?,
    val sentiment: String?,
    val barriers: Int?,
)

data class DerivCandle(
    val epochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

data class DerivOpenContract(
    val contractId: Long,
    val contractType: String,
    val currency: String,
    val symbol: String?,
    val buyPrice: Double?,
    val bidPrice: Double?,
    val payout: Double?,
    val profit: Double?,
    val currentSpot: Double?,
    val exitSpot: Double?,
    val isSold: Boolean,
)

data class DerivTransaction(
    val transactionId: Long?,
    val action: String?,
    val amount: Double?,
    val symbol: String?,
    val epochSeconds: Long?,
)

data class DerivContractUpdate(
    val stopLoss: Double?,
    val takeProfit: Double?,
)

data class DerivContractUpdateHistoryEntry(
    val displayName: String,
    val orderAmount: Double?,
    val orderDateEpochSeconds: Long,
    val orderType: String,
    val value: Double?,
)

data class DerivContractCategory(
    val contractType: String,
    val category: String?,
    val displayName: String?,
)

data class DerivProfitRecord(
    val transactionId: Long,
    val contractId: Long?,
    val contractType: String?,
    val symbol: String?,
    val buyPrice: Double,
    val sellPrice: Double,
    val payout: Double,
    val purchaseTimeEpochSeconds: Long,
    val sellTimeEpochSeconds: Long?,
)

data class DerivStatementRecord(
    val transactionId: Long,
    val actionType: String,
    val amount: Double,
    val balanceAfter: Double,
    val transactionTimeEpochSeconds: Long,
    val contractId: Long?,
    val symbol: String?,
)

data class DerivWalletBalance(
    val currency: String,
    val balance: String,
    val input: String,
    val output: String,
)

data class DerivWallet(
    val walletId: String,
    val type: String,
    val balances: List<DerivWalletBalance>,
    val convertedTo: String?,
    val approximateTotalBalance: String?,
)

data class DerivWalletTransaction(
    val requestId: String,
    val transactionId: Long,
    val timestamp: String,
    val category: String,
    val channel: String,
    val status: String,
    val grossAmount: String,
    val netAmount: String,
    val currency: String,
)

data class DerivWalletTransactionsPage(
    val transactions: List<DerivWalletTransaction>,
    val nextPageUrl: String?,
    val previousPageUrl: String?,
)

