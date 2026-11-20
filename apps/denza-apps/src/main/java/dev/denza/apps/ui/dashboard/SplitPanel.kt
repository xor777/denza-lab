package dev.denza.apps.ui.dashboard

import dev.denza.apps.R
import dev.denza.apps.core.AppText

/**
 * The split panel's two switches as words: «Значок на рабочем столе», the feature's own switch,
 * and «Пилюля», which stands on it the way the HUD panel's rows stand on «Манёвры» - greyed while
 * the split is off or settling, keeping its own value.
 *
 * Plain Kotlin: [splitSheet] draws it, `SplitPanelTest` holds its rules.
 */
internal object SplitPanel {

    fun of(icon: Boolean, pill: Boolean, busy: Boolean): List<SplitPanelRow> = listOf(
        SplitPanelRow(SplitPanelRow.Id.ICON, AppText.s(R.string.sheet_split_icon), null, icon, !busy),
        SplitPanelRow(
            SplitPanelRow.Id.PILL,
            AppText.s(R.string.sheet_split_pill),
            AppText.s(R.string.sheet_split_pill_summary),
            pill,
            icon && !busy,
        ),
    )
}

/** One switch of the split panel. */
internal data class SplitPanelRow(
    val id: Id,
    val title: String,
    val summary: String?,
    val checked: Boolean,
    val enabled: Boolean,
) {
    enum class Id { ICON, PILL }
}
