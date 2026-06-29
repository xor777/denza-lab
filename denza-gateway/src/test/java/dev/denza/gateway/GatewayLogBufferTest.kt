package dev.denza.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayLogBufferTest {
    @Test
    fun keepsOnlyNewestEntries() {
        var now = 1L
        val buffer = GatewayLogBuffer(capacity = 2, clock = { now++ })

        buffer.add(LogLevel.Info, "one")
        buffer.add(LogLevel.Warn, "two")
        val snapshot = buffer.add(LogLevel.Error, "three")

        assertEquals(listOf("two", "three"), snapshot.map { it.message })
        assertEquals(listOf(LogLevel.Warn, LogLevel.Error), snapshot.map { it.level })
    }
}
