package com.foxtrader.app.data.market.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Liveness detection: a ping is due on schedule, and a connection is only dead
 * when an outstanding ping goes unanswered past the timeout. A pong always
 * clears the outstanding-ping condition.
 */
class HeartbeatMonitorTest {

    private class Clock(var t: Long = 0L)

    @Test
    fun `does nothing until started`() {
        val clock = Clock()
        val monitor = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 3_000, now = { clock.t })
        clock.t = 10_000
        assertFalse(monitor.pingDue())
        assertFalse(monitor.isTimedOut())
    }

    @Test
    fun `ping is not due before the interval elapses`() {
        val clock = Clock()
        val monitor = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 3_000, now = { clock.t })
        monitor.start()
        clock.t = 999
        assertFalse(monitor.pingDue())
        clock.t = 1_000
        assertTrue(monitor.pingDue())
    }

    @Test
    fun `sending a ping restarts the interval`() {
        val clock = Clock()
        val monitor = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 5_000, now = { clock.t })
        monitor.start()
        clock.t = 1_000
        assertTrue(monitor.pingDue())
        monitor.onPingSent()
        assertFalse(monitor.pingDue())
        clock.t = 1_999
        assertFalse(monitor.pingDue())
    }

    @Test
    fun `times out when a ping goes unanswered`() {
        val clock = Clock()
        val monitor = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 3_000, now = { clock.t })
        monitor.start()
        clock.t = 1_000
        monitor.onPingSent()
        clock.t = 3_999
        assertFalse(monitor.isTimedOut())
        clock.t = 4_000
        assertTrue(monitor.isTimedOut())
    }

    @Test
    fun `a pong clears the timeout condition`() {
        val clock = Clock()
        val monitor = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 3_000, now = { clock.t })
        monitor.start()
        clock.t = 1_000
        monitor.onPingSent()
        clock.t = 2_000
        monitor.onPong()
        // Well past the would-be timeout, but the pong answered the ping.
        clock.t = 10_000
        assertFalse(monitor.isTimedOut())
    }

    @Test
    fun `no timeout while no ping is outstanding`() {
        val clock = Clock()
        val monitor = HeartbeatMonitor(intervalMs = 1_000, timeoutMs = 3_000, now = { clock.t })
        monitor.start()
        clock.t = 1_000_000
        // Ping is overdue, but nothing has been sent and unanswered.
        assertFalse(monitor.isTimedOut())
    }
}
