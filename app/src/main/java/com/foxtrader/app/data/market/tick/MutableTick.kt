package com.foxtrader.app.data.market.tick

import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.data.market.model.TickSide

/**
 * A mutable, reusable tick used on the hot ingestion path.
 *
 * Live feeds can deliver many ticks per second. Allocating an immutable
 * [Tick] per frame puts pressure on the garbage collector and is a direct cause
 * of frame-time spikes. The engine instead [acquire][TickPool.acquire]s a
 * [MutableTick], fills it in place, hands it to consumers that copy what they
 * need, and then [release][TickPool.release]s it back to the pool — zero
 * allocations in steady state.
 */
class MutableTick {
    var symbol: String = ""
        private set
    var price: Double = 0.0
        private set
    var quantity: Double = 0.0
        private set
    var timestamp: Long = 0L
        private set
    var side: TickSide = TickSide.UNKNOWN
        private set

    fun set(
        symbol: String,
        price: Double,
        quantity: Double,
        timestamp: Long,
        side: TickSide = TickSide.UNKNOWN,
    ): MutableTick = apply {
        this.symbol = symbol
        this.price = price
        this.quantity = quantity
        this.timestamp = timestamp
        this.side = side
    }

    fun copyFrom(tick: Tick): MutableTick =
        set(tick.symbol, tick.price, tick.quantity, tick.timestamp, tick.side)

    /** Materialises a stable immutable snapshot of the current contents. */
    fun toTick(): Tick = Tick(symbol, price, quantity, timestamp, side)

    /** Clears the contents so a pooled instance never leaks a previous value. */
    fun reset() {
        symbol = ""
        price = 0.0
        quantity = 0.0
        timestamp = 0L
        side = TickSide.UNKNOWN
    }
}

/**
 * A bounded object pool of [MutableTick] instances.
 *
 * Pre-allocates [initialCapacity] instances. [acquire] returns a pooled instance
 * or, if the pool is exhausted, allocates a fresh one (graceful degradation —
 * the engine never blocks on the pool). [release] resets and returns an instance
 * to the pool. The pool never grows its free list beyond what has been released,
 * so memory stays bounded by the high-water mark of concurrent use.
 */
class TickPool(initialCapacity: Int = DEFAULT_INITIAL_CAPACITY) {

    private val free = ArrayDeque<MutableTick>(initialCapacity)

    init {
        require(initialCapacity >= 0) { "initialCapacity must be >= 0" }
        repeat(initialCapacity) { free.addLast(MutableTick()) }
    }

    /** Number of instances currently available without allocating. */
    val available: Int get() = free.size

    /** Total instances handed out and not yet released. */
    var outstanding: Int = 0
        private set

    fun acquire(): MutableTick {
        outstanding++
        return free.removeLastOrNull() ?: MutableTick()
    }

    fun release(tick: MutableTick) {
        if (outstanding > 0) outstanding--
        tick.reset()
        free.addLast(tick)
    }

    companion object {
        const val DEFAULT_INITIAL_CAPACITY = 64
    }
}
