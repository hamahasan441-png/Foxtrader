package com.foxtrader.app.data.market.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scriptable [WebSocketTransport] for driving [RealtimeConnection] under test.
 *
 * The test feeds events with the `emit*` helpers; [connect] replays them as a cold
 * flow that completes after the first terminal ([TransportEvent.Closed] or
 * [TransportEvent.Failed]) event, exactly like the real transport contract. Frames
 * the driver sends (heartbeat pings) are recorded in [sentFrames], and a
 * driver-initiated [close] surfaces back through the stream as an abnormal
 * [TransportEvent.Closed] so the reconnect path is exercised.
 *
 * One instance models one connection; a reconnecting driver obtains a fresh
 * instance from its transport factory per attempt.
 */
class FakeWebSocketTransport : WebSocketTransport {

    private val incoming = Channel<TransportEvent>(Channel.UNLIMITED)

    /** Outbound frames the driver sent (e.g. heartbeat pings), in order. */
    val sentFrames = mutableListOf<String>()

    /** The URL this socket was opened against (set by [connect]). */
    var connectedUrl: String? = null
        private set

    /** True once [close] has been called (by the driver) or a terminal event fed in. */
    var isClosed: Boolean = false
        private set

    /** The close code used by a driver-initiated [close], if any. */
    var closeCode: Int? = null
        private set

    override fun connect(url: String): Flow<TransportEvent> = flow {
        connectedUrl = url
        for (event in incoming) {
            emit(event)
            if (event is TransportEvent.Closed || event is TransportEvent.Failed) break
        }
    }

    override fun send(text: String): Boolean {
        if (isClosed) return false
        sentFrames.add(text)
        return true
    }

    override fun close(code: Int, reason: String) {
        if (isClosed) return
        isClosed = true
        closeCode = code
        incoming.trySend(TransportEvent.Closed(code, reason))
        incoming.close()
    }

    // -- test scripting helpers -------------------------------------------------

    fun emitOpened() {
        incoming.trySend(TransportEvent.Opened)
    }

    fun emitText(payload: String) {
        incoming.trySend(TransportEvent.Text(payload))
    }

    fun emitClosed(code: Int = NORMAL_CLOSE_CODE, reason: String = "") {
        isClosed = true
        incoming.trySend(TransportEvent.Closed(code, reason))
        incoming.close()
    }

    fun emitFailed(cause: Throwable = RuntimeException("connection failed")) {
        isClosed = true
        incoming.trySend(TransportEvent.Failed(cause))
        incoming.close()
    }

    private companion object {
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
