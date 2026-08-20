package com.foxtrader.app.data.repository

import kotlinx.coroutines.CancellationException

/** Result must never turn structured coroutine cancellation into a normal failure value. */
internal fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure { error ->
    if (error is CancellationException) throw error
}
