package com.foxtrader.app.domain.usecase.deriv

import com.foxtrader.app.domain.model.deriv.DerivAccount
import com.foxtrader.app.domain.model.deriv.DerivAccountType
import com.foxtrader.app.domain.model.deriv.DerivExecutionAuthorization
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivExecutionAuthorizationTest {
    private val real = DerivAccount("CR100", DerivAccountType.REAL, "USD", 1000.0)
    private val otherReal = DerivAccount("CR200", DerivAccountType.REAL, "USD", 1000.0)
    private val demo = DerivAccount("VRTC100", DerivAccountType.DEMO, "USD", 10000.0)

    @Test
    fun realRequiresFreshManualConfirmation() {
        assertFalse(
            DerivExecutionAuthorization("CR100", DerivAccountType.REAL, false, 1_000)
                .canSubmitFor(real, 1_001)
        )
        assertTrue(
            DerivExecutionAuthorization("CR100", DerivAccountType.REAL, true, 10_000, 30_000)
                .canSubmitFor(real, 20_000)
        )
        assertFalse(
            DerivExecutionAuthorization("CR100", DerivAccountType.REAL, true, 10_000, 30_000)
                .canSubmitFor(real, 50_001)
        )
    }

    @Test
    fun futureReviewTimestampIsRejected() {
        assertFalse(
            DerivExecutionAuthorization("CR100", DerivAccountType.REAL, true, 20_001, 30_000)
                .canSubmitFor(real, 20_000)
        )
    }

    @Test
    fun authorizationCannotBeReusedAcrossAccountsOfSameType() {
        val authorization = DerivExecutionAuthorization("CR100", DerivAccountType.REAL, true, 10_000)
        assertTrue(authorization.canSubmitFor(real, 10_001))
        assertFalse(authorization.canSubmitFor(otherReal, 10_001))
    }

    @Test
    fun demoSkipsRealMoneyConfirmationButStillRequiresAccountIdentity() {
        val authorization = DerivExecutionAuthorization("VRTC100", DerivAccountType.DEMO, false, 0)
        assertTrue(authorization.canSubmitFor(demo, Long.MAX_VALUE))
        assertFalse(authorization.canSubmitFor(real, Long.MAX_VALUE))
    }
    @Test
    fun unknownAccountTypeIsNeverExecutionEligible() {
        val unknown = DerivAccount("UNKNOWN1", DerivAccountType.UNKNOWN, "USD", null, null, null)
        val authorization = DerivExecutionAuthorization("UNKNOWN1", DerivAccountType.UNKNOWN, true, 10_000)
        assertFalse(authorization.canSubmitFor(unknown, 10_001))
    }

}
