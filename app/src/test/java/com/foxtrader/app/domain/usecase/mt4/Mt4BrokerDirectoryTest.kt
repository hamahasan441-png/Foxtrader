package com.foxtrader.app.domain.usecase.mt4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mt4BrokerDirectoryTest {

    private val directory = Mt4BrokerDirectory()

    @Test
    fun `blank query returns the full directory`() {
        val all = directory.search("")
        assertTrue(all.isNotEmpty())
        assertEquals(all, directory.all())
    }

    @Test
    fun `search matches by broker name case-insensitively`() {
        val results = directory.search("ic markets")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.name.equals("IC Markets", ignoreCase = true) })
    }

    @Test
    fun `search matches by server string`() {
        val results = directory.search("ICMarkets-Demo")
        assertTrue(results.any { it.servers.any { s -> s.equals("ICMarkets-Demo", ignoreCase = true) } })
    }

    @Test
    fun `search by partial server works`() {
        val results = directory.search("pepperstone")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.name.contains("Pepperstone", ignoreCase = true) })
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(directory.search("zzz-no-such-broker-12345").isEmpty())
    }

    @Test
    fun `every broker exposes at least one non blank server`() {
        directory.all().forEach { broker ->
            assertFalse(broker.servers.isEmpty())
            assertTrue(broker.servers.all { it.isNotBlank() })
        }
    }
}
