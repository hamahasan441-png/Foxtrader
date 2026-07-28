package com.foxtrader.app.data.market.transport

import kotlinx.coroutines.flow.Flow

/**
 * Events surfaced by a single [WebSocketTransport] connection.
 *
 * Modelling the socket as a stream of events (rather than scattered callbacks)
 * lets the reconnecting driver express its whole lifecycle as flow collection:
 * open → messages → terminal (closed/failed). A terminal event ends the flow.
 */
sealed interface TransportEvent {

    /** The socket handshake completed; the feed is live. */
    data object Opened : TransportEvent

    /** An inbound text frame (typically JSON to be handed to a TickDecoder). */
    data class Text(val payload: String) : TransportEvent

    /** The socket closed. A normal close (code 1000) is not an error. */
    data class Closed(val code: Int, val reason: String) : TransportEvent

    /** The connection failed (I/O error, handshake rejection, timeout). */
    data class Failed(val cause: Throwable) : TransportEvent
}

/**
 * The lowest-level socket seam. One implementation wraps OkHttp; tests supply a
 * fake. Everything above this (reconnect, heartbeat, failover, decoding) is
 * transport-agnostic, so the OkHttp dependency never leaks into business logic.
 *
 * Contract:
 *  - [connect] returns a cold [Flow] that emits [TransportEvent.Opened], then a
 *    sequence of [TransportEvent.Text], then exactly one terminal
 *    [TransportEvent.Closed] or [TransportEvent.Failed], after which it completes.
 *  - [send] queues an outbound text frame; returns false if not currently open.
 *  - [close] terminates the connection idempotently.
 */
interface WebSocketTransport {
    fun connect(url: String): Flow<TransportEvent>
    fun send(text: String): Boolean
    fun close(code: Int = 1000, reason: String = "client close")
}
