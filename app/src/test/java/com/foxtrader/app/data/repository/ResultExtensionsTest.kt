package com.foxtrader.app.data.repository

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Test

class ResultExtensionsTest {

    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown`() {
        Result.failure<Unit>(CancellationException("cancelled")).rethrowCancellation()
    }

    @Test
    fun `ordinary failure stays in result`() {
        val failure = IllegalStateException("failed")

        val result = Result.failure<Unit>(failure).rethrowCancellation()

        assertSame(failure, result.exceptionOrNull())
    }
}
