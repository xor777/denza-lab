package dev.denza.apps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.denza.apps.design.DenzaColors
import dev.denza.apps.design.DenzaIcons
import dev.denza.apps.feature.adb.AdbExplainer
import dev.denza.apps.feature.adb.ServiceEntryTaps
import dev.denza.apps.ui.components.DenzaSection
import dev.denza.apps.ui.components.DenzaSheet
import dev.denza.apps.ui.components.DenzaSheetHeader

/**
 * What ADB is, and what to do about it - offered from the gate that blocks everything else.
 *
 * It is a [DenzaSheet] rather than anything of its own, and that is load-bearing twice over. A sheet
 * is the app's one panel, so this window cannot drift from the rest of the screen; and a sheet is a
 * [androidx.compose.ui.window.Dialog], which puts it in a window of its own above the activity - the
 * only reason a finger can reach it while the gate's full-screen shield is swallowing every touch in
 * the activity's own window.
 *
 * Seven taps on the title open the service screen. That is the point of the whole window: the gate
 * covers the dashboard, the dashboard holds the service tile, and without a door here a car with its
 * ADB switch off can show its owner neither the cause nor a single reading.
 */
@Composable
internal fun AdbExplainerSheet(
    compact: Boolean,
    onOpenService: () -> Unit,
    onDismiss: () -> Unit,
) {
    val taps = remember { ServiceEntryTaps() }
    DenzaSheet(onDismiss = onDismiss, compact = compact) {
        DenzaSheetHeader(
            title = AdbExplainer.TITLE,
            subtitle = "",
            onDismiss = onDismiss,
            icon = DenzaIcons.Service,
            onTitleTap = {
                if (taps.tap(System.currentTimeMillis())) {
                    onDismiss()
                    onOpenService()
                }
            },
        )
        DenzaSection(title = AdbExplainer.WHAT_IT_IS_TITLE) {
            Paragraph(AdbExplainer.WHAT_IT_IS)
        }
        DenzaSection(title = AdbExplainer.HOW_TO_OPEN_TITLE) {
            Paragraph(AdbExplainer.HOW_TO_OPEN)
        }
    }
}

/**
 * A paragraph of the explanation.
 *
 * Ink rather than the muted grey a caption would take: these two paragraphs are the content of the
 * window, not a note beside something else.
 */
@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = DenzaColors.Ink,
    )
}
