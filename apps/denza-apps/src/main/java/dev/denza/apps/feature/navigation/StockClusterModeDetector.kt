package dev.denza.apps.feature.navigation

/** Detects the stock map scene from the task that BYD places on the selected cluster display. */
internal class StockClusterModeDetector(
    private val shell: (String) -> String,
) {
    fun isMapVisible(clusterDisplayId: Int): Boolean =
        isMapVisible(shell("am stack list"), clusterDisplayId)

    companion object {
        private const val STOCK_MAP_COMPONENT =
            "com.byd.launchermap/com.byd.automap.meter.MeterActivity"
        private val rootPattern = Regex("^RootTask id=\\d+ .* displayId=(\\d+)")
        private val taskPattern = Regex(
            "^\\s+taskId=\\d+:\\s+([^\\s]+).*\\svisible=(true|false)(?:\\s|$)",
        )

        internal fun isMapVisible(snapshot: String, clusterDisplayId: Int): Boolean {
            var displayId: Int? = null
            for (line in snapshot.lineSequence()) {
                rootPattern.find(line)?.let { match ->
                    displayId = match.groupValues[1].toInt()
                    return@let
                }
                val task = taskPattern.find(line) ?: continue
                if (
                    displayId == clusterDisplayId &&
                    task.groupValues[1] == STOCK_MAP_COMPONENT &&
                    task.groupValues[2].toBoolean()
                ) {
                    return true
                }
            }
            return false
        }
    }
}
