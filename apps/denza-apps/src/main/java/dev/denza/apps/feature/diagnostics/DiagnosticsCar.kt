package dev.denza.apps.feature.diagnostics

import java.util.concurrent.TimeUnit

/**
 * Which car «Сервис → Диагностика» is for. Its catalog of 68 blocks, its schemes and its readings
 * were all taken from one model, the Z9GT DM (`AutoType` 170), and the shell helper refuses to put
 * a request on any other car's bus (docs/vehicle-diagnostics-architecture.md, 3.3). On another car
 * the section could only stand empty, so «Сервис» has no row for it there - and none while the car
 * is not known, since a row that appears and then turns out to be nothing is worse than one a
 * moment late.
 */
internal object DiagnosticsCar {
    /** The Z9GT's `persist.sys.AutoType` (docs/vehicle-data-findings.md, "Which car is this"). */
    const val AUTO_TYPE = "170"
    const val PROPERTY = "persist.sys.AutoType"

    /** How long `getprop` may take before the car counts as not known yet. */
    const val GETPROP_TIMEOUT_MS = 2_000L

    /** Whether «Сервис» has the «Диагностика» row on the car whose `AutoType` is [autoType]. */
    fun offered(autoType: String?): Boolean = autoType?.trim() == AUTO_TYPE

    /** The car once read: the property is written at boot and stays, so one answer serves the process. */
    @Volatile var known: String? = null
        private set

    /**
     * `persist.sys.AutoType`, or null when the property is empty or cannot be read; blocking, never on the
     * main thread. The hidden `SystemProperties.get` first, then `/system/bin/getprop` from the app UID - the
     * route the findings measured (docs/vehicle-data-findings.md, "Which car is this") - bounded by
     * [GETPROP_TIMEOUT_MS], for a firmware that refuses the hidden method.
     */
    fun autoType(): String? = known ?: autoType(::reflected, ::getprop)?.also { known = it }

    /** [routes] in order: the first that gives a value. */
    fun autoType(vararg routes: () -> String?): String? =
        routes.firstNotNullOfOrNull { route -> runCatching(route).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } }

    private fun reflected(): String? =
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, PROPERTY) as? String

    private fun getprop(): String? {
        val process = ProcessBuilder("/system/bin/getprop", PROPERTY).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(GETPROP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            if (process.exitValue() != 0) return null
            // A property is one short line: it is in the pipe by the time the process has ended.
            return process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            process.destroy()
        }
    }
}
