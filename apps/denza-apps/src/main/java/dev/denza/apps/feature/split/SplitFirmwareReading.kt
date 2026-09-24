package dev.denza.apps.feature.split

/**
 * What the service's technical page says about the firmware's own split, read in this process.
 *
 * Split Screen stopped working in 0.7.0-alpha on a firmware nobody here can reach with ADB, and
 * the owners of those cars can send a photo of a page and nothing else. The two rows this makes are
 * the part of the answer that differs from one firmware to the next: which split the firmware says
 * it holds, and whether the three things the product hears from it in-process - Home, the area push
 * and the BYD binder calls - are heard at all.
 */
internal data class SplitFirmwareReading(
    /**
     * `byd_smart_multi_split_window_mode`, or `null` when it could not be read. A hint rather than
     * the firmware's memory: the SmartMulti lease writes this key back at the end of every session
     * of ours (findings, "The mode is published in Settings.System"), so between sessions it says
     * what was there before the session, not what the firmware last did.
     */
    val mode: Int?,
    /** `getScreenAreaInfoForMulti`, read in this process; `null` when unreadable. */
    val area: Int?,
    /** Whether the homekey receiver is registered now ([SplitFirmwareSignals.homeKeyHeard]). */
    val homeKeyHeard: Boolean,
    /** Whether the area listener is registered now ([SplitFirmwareSignals.areaHeard]). */
    val areaHeard: Boolean,
    /** How the last in-process BYD call went ([SplitInProcessHealth]); `null` before the first. */
    val callsOk: Boolean?,
) {
    /** «Сплит прошивки»: `две панели · область 3`, `режим ? · область ?`. */
    fun split(): String = "${modeWords(mode)} · область ${area ?: "?"}"

    /** «Сигналы прошивки»: `Home да · область да · вызовы не было`. */
    fun signals(): String =
        "Home ${yesNo(homeKeyHeard)} · область ${yesNo(areaHeard)} · " +
            "вызовы ${callsOk?.let(::yesNo) ?: "не было"}"

    companion object {
        /** The `Settings.System` key SmartMulti publishes its mode in. */
        const val MODE_KEY = "byd_smart_multi_split_window_mode"

        /** 100 balanced, 101 the primary (narrow) side expanded, 102 the secondary (wide) side. */
        fun modeWords(mode: Int?): String = when (mode) {
            100 -> "две панели"
            101 -> "одна узкая"
            102 -> "одна широкая"
            else -> "режим ?"
        }

        private fun yesNo(value: Boolean) = if (value) "да" else "нет"
    }
}
