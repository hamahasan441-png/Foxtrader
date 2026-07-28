package com.foxtrader.app.data.market.decode

import com.foxtrader.app.data.market.model.Tick

/**
 * Turns one inbound WebSocket frame into a [Tick], or `null` when the frame is
 * not a trade tick (heartbeats, subscription acks, depth snapshots, etc.).
 *
 * Decoding is its own seam so a provider's wire format never leaks past the
 * transport: the engine only ever sees [Tick]s. Each provider supplies one
 * decoder; switching providers swaps the decoder, nothing else.
 */
fun interface TickDecoder {
    /** Parses [frame] into a [Tick], or returns `null` if it is not a trade tick. */
    fun decode(frame: String): Tick?
}
