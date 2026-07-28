package com.foxtrader.app.data.market.tick

import com.foxtrader.app.data.market.model.Tick

/**
 * A fixed-capacity ring buffer of recent ticks — the engine's short-term memory.
 *
 * Why it exists:
 *  - **Bounded memory.** A live feed must never grow a buffer without bound
 *    (the masterplan's A5 finding). When full, the oldest tick is overwritten.
 *  - **Zero-allocation ingest.** Slots are pre-allocated [MutableTick]s; [add]
 *    copies fields in place, so steady-state ingestion allocates nothing.
 *  - **Replay.** [snapshot] / [drainTo] re-emit the buffered ticks oldest→newest,
 *    which is what a reconnect or a test harness needs to re-drive downstream
 *    consumers from a known point.
 *
 * Not thread-safe by design: the engine funnels all ticks through a single
 * dispatcher, so the buffer is only ever touched from one thread. Adding locks
 * here would only tax the hot path.
 */
class TickBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val slots = Array(capacity) { MutableTick() }
    private var head = 0          // index of the oldest element
    private var count = 0         // number of occupied slots

    /** Total ticks dropped because the buffer was full. Monotonic. */
    var droppedCount: Long = 0L
        private set

    val size: Int get() = count
    val isFull: Boolean get() = count == capacity
    val isEmpty: Boolean get() = count == 0

    /**
     * Appends a tick. If the buffer is full the oldest tick is overwritten and
     * [droppedCount] is incremented. Allocation-free in steady state.
     */
    fun add(tick: Tick) {
        val index = (head + count) % capacity
        slots[index].copyFrom(tick)
        if (count == capacity) {
            // Overwrite the oldest slot and advance head past it.
            head = (head + 1) % capacity
            droppedCount++
        } else {
            count++
        }
    }

    /** Convenience overload for the pooled ingestion path. */
    fun add(tick: MutableTick) {
        val index = (head + count) % capacity
        slots[index].set(tick.symbol, tick.price, tick.quantity, tick.timestamp, tick.side)
        if (count == capacity) {
            head = (head + 1) % capacity
            droppedCount++
        } else {
            count++
        }
    }

    /** The most recently added tick, or `null` when empty. Allocates one [Tick]. */
    fun latest(): Tick? =
        if (count == 0) null else slots[(head + count - 1) % capacity].toTick()

    /** The oldest buffered tick, or `null` when empty. Allocates one [Tick]. */
    fun oldest(): Tick? =
        if (count == 0) null else slots[head].toTick()

    /** An ordered (oldest→newest) immutable copy of the buffer. On-demand only. */
    fun snapshot(): List<Tick> {
        val out = ArrayList<Tick>(count)
        drainTo(out)
        return out
    }

    /**
     * Appends the buffered ticks (oldest→newest) to [out] and returns it, reusing
     * the caller's list to avoid an extra allocation. Used for replay.
     */
    fun <T : MutableList<Tick>> drainTo(out: T): T {
        for (i in 0 until count) {
            out.add(slots[(head + i) % capacity].toTick())
        }
        return out
    }

    fun clear() {
        head = 0
        count = 0
        // Note: droppedCount is intentionally retained as a lifetime metric.
    }
}
