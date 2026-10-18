package dev.denza.apps.feature.network

/** Effective package state for user 0, not the traffic allowance or an internet-health verdict. */
internal enum class TrafficMonitorState { UNKNOWN, ENABLED, DISABLED, ABSENT }

/** Only the stock package's enabled state is writable. No counters, APNs or network rules. */
internal object TrafficMonitorProtocol {
    const val PACKAGE = "com.byd.trafficmonitor"
    private val fields = listOf("all" to "", "enabled" to "-e ", "disabled" to "-d ")

    val readCommand = fields.joinToString("; ") { (field, flag) ->
        "echo '@@traffic:$field'; pm list packages ${flag}--user 0 $PACKAGE; " +
            "echo \"@@traffic:$field:exit=${'$'}?\""
    }

    val disableCommand =
        "pm disable-user --user 0 $PACKAGE; " +
            "echo \"@@traffic:write:exit=${'$'}?\""

    /** A missing/truncated/failed read must never turn into an absent or disabled package. */
    fun parse(output: String): TrafficMonitorState {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        var offset = 0
        val present = mutableListOf<Boolean>()
        for ((field, _) in fields) {
            if (lines.getOrNull(offset++) != "@@traffic:$field") return TrafficMonitorState.UNKNOWN
            var found = false
            while (lines.getOrNull(offset)?.startsWith("package:") == true) {
                val line = lines[offset++]
                if (line == "package:$PACKAGE") {
                    if (found) return TrafficMonitorState.UNKNOWN
                    found = true
                }
            }
            if (lines.getOrNull(offset++) != "@@traffic:$field:exit=0") return TrafficMonitorState.UNKNOWN
            present += found
        }
        if (offset != lines.size) return TrafficMonitorState.UNKNOWN
        return when (present) {
            listOf(false, false, false) -> TrafficMonitorState.ABSENT
            listOf(true, true, false) -> TrafficMonitorState.ENABLED
            listOf(true, false, true) -> TrafficMonitorState.DISABLED
            else -> TrafficMonitorState.UNKNOWN // including a package changing during the read
        }
    }

    fun writeCompleted(output: String): Boolean =
        output.lineSequence().map(String::trim).filter(String::isNotEmpty).lastOrNull() == "@@traffic:write:exit=0"
}
