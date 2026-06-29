package dev.denza.gateway

class GatewayLogBuffer(
    private val capacity: Int = 200,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = ArrayDeque<LogEntry>()

    @Synchronized
    fun add(level: LogLevel, message: String): List<LogEntry> {
        if (entries.size == capacity) {
            entries.removeFirst()
        }
        entries.addLast(LogEntry(clock(), level, message))
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()
}
