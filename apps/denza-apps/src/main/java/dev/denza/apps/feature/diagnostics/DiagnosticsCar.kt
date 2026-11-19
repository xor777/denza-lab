package dev.denza.apps.feature.diagnostics

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

    /** Whether «Сервис» has the «Диагностика» row on the car whose `AutoType` is [autoType]. */
    fun offered(autoType: String?): Boolean = autoType?.trim() == AUTO_TYPE

    /**
     * `persist.sys.AutoType`, which the app UID reads directly (docs/vehicle-data-findings.md, "Which
     * car is this"); null when the property is empty or cannot be read. It is written at boot and
     * stays, so «Сервис» reads it each time it opens rather than watching it.
     */
    fun autoType(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "persist.sys.AutoType") as? String
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
