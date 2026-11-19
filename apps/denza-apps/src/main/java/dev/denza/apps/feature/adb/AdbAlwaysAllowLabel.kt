package dev.denza.apps.feature.adb

import java.util.Locale

/**
 * The box on the car's own ADB prompt, worded as the car words it.
 *
 * SystemUI's `usb_debugging_always`, read from this car's firmware; see
 * docs/adb-authorization-recovery.md, "Approved without «always allow»". The prompt is drawn in
 * the car's language, not in this app's, so the words follow the system locale and are never
 * extracted to strings.xml: a translation of them would name a box the driver cannot find.
 */
internal object AdbAlwaysAllowLabel {
    private const val DEFAULT = "Always allow from this computer"
    private const val RUSSIAN = "Всегда разрешать отладку с этого компьютера"
    private const val CHINESE = "一律允许使用这台计算机进行调试"
    private const val CHINESE_TAIWAN = "一律允許透過這台電腦進行"
    private const val CHINESE_HONG_KONG = "一律允許透過這部電腦進行"

    fun of(locale: Locale = Locale.getDefault()): String = when (locale.language) {
        "ru" -> RUSSIAN
        "zh" -> when (locale.country) {
            "TW" -> CHINESE_TAIWAN
            "HK", "MO" -> CHINESE_HONG_KONG
            else -> CHINESE
        }
        else -> DEFAULT
    }
}
