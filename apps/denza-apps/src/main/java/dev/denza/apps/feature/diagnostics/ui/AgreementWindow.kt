package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.design.luminofor.LuminoforSpec.Sheet
import dev.denza.apps.feature.diagnostics.AgreementBox
import dev.denza.apps.ui.components.BaselineText
import dev.denza.apps.ui.components.DenzaModalDialog
import dev.denza.apps.ui.components.DenzaPrimaryButton
import dev.denza.apps.ui.components.DenzaTickPlate
import dev.denza.apps.ui.components.ParagraphText
import dev.denza.apps.ui.components.SheetInk
import dev.denza.apps.ui.components.centredBaseline

/**
 * «О разделе», the window in front of the section until the driver agrees ([dev.denza.apps.feature.diagnostics.DiagnosticsAgreement]):
 * the stock dialog card (the board's `drawModal`, `modal-diagnostics` and `-agreed`) with the title
 * alone in the icon's row, three paragraphs, the box and «ОК» across the card.
 *
 * [onAgree] comes only from «ОК» with the box ticked. [onLeave] is Back and a tap on the dark round
 * the card: the window closes and nothing is stored. The tick is saved with the screen, so a
 * recreated activity brings the window back as it was; a window opened again starts unticked.
 * [ticked] is the box as the window opens - a board's, in the debug build.
 */
@Composable
internal fun AgreementWindow(
    compact: Boolean,
    onAgree: () -> Unit,
    onLeave: () -> Unit,
    ticked: Boolean = false,
) {
    var box by rememberSaveable(stateSaver = AgreementBoxSaver) { mutableStateOf(AgreementBox(ticked)) }
    val m = Sheet.Modal
    DenzaModalDialog(compact = compact, onDismiss = onLeave) {
        val size = if (compact) m.COMPACT_TITLE_SIZE else m.TITLE_SIZE
        Box(Modifier.fillMaxWidth().height(m.ICON.dp)) {
            BaselineText(
                text = AppText.s(R.string.diagnostics_agreement_title),
                style = SheetInk.style(size, 500),
                baseline = centredBaseline(m.ICON / 2f, size),
            )
        }
        AGREEMENT_PARAGRAPHS.forEach { id ->
            ParagraphText(
                AppText.s(id),
                style = SheetInk.style(m.TEXT_SIZE, 400, SheetInk.white(m.TEXT_ALPHA), Sheet.Note.LEADING),
                size = m.TEXT_SIZE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DenzaTickPlate(AppText.s(R.string.diagnostics_agreement_tick), ticked = box.ticked, onToggle = { box = box.toggled() })
        DenzaPrimaryButton(
            text = AppText.s(R.string.diagnostics_agreement_ok),
            onClick = { if (box.confirm()) onAgree() },
            modifier = Modifier.fillMaxWidth(),
            enabled = box.okEnabled,
        )
    }
}

/** The window's paragraphs, in order: who cannot help, what the section is, where to go. */
internal val AGREEMENT_PARAGRAPHS = listOf(
    R.string.diagnostics_agreement_author,
    R.string.diagnostics_agreement_purpose,
    R.string.diagnostics_agreement_service,
)

private val AgreementBoxSaver = androidx.compose.runtime.saveable.Saver<AgreementBox, Boolean>(
    save = { it.ticked },
    restore = { AgreementBox(it) },
)
