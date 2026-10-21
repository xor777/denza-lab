package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.denza.apps.feature.diagnostics.scan.GbFlagState
import dev.denza.apps.feature.diagnostics.scan.GbFlagView
import dev.denza.apps.feature.diagnostics.scan.GbFlagsView
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Ink
import dev.denza.apps.feature.diagnostics.ui.ScannerSpec.Type

/*
 * «Тяговая батарея · Флаги GB/T 32960» (architecture, 7.2; `scanner.js` `sysGbFlags()`, boards
 * `scanner-sys-pack-flags`, `-flags-alarm`): GB/T 32960.3's general alarm flags as the stock reporter
 * sets them, a tile a flag in the standard's bit order - the pack's nine on two rows, the drive's and
 * high voltage's five on the third - each with its bit, its FID, its name, its word and the code
 * read. A flag raised is lit orange; one not read is dark and says «н/д». The count of the raised
 * ones stands at the head's right.
 */

/** The tab's body under the system's head and tabs. */
@Composable
internal fun GbFlagTiles(view: GbFlagsView) {
    val g = ScannerSpec.GbFlags
    val w = LocalScanWidth.current
    val tw = (w - 48f - (g.COLS - 1) * g.GAP) / g.COLS
    StatsHead(view.head, view.counters, g.HEAD_Y, w)
    view.groups.forEachIndexed { gi, group ->
        ScanText(group.title, Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(24f, g.GROUP_Y[gi]))
        group.flags.forEachIndexed { i, f ->
            val row = if (gi == 0) i / g.COLS else g.LATER_ROW
            FlagTile(f, 24f + (i % g.COLS) * (tw + g.GAP), g.ROW_Y[row], tw)
        }
    }
}

/** One flag: «бит N» and its FID along the top, its name, then its word and the code read. */
@Composable
private fun FlagTile(f: GbFlagView, x: Float, y: Float, tw: Float) {
    val g = ScannerSpec.GbFlags
    val (fill, line, word) = when (f.state) {
        GbFlagState.ALARM -> Triple(Ink.ORANGE_FILL, Ink.ORANGE, Ink.ORANGE)
        GbFlagState.NONE -> Triple(Ink.TILE_UNREAD, Ink.LINE3, Ink.FAINT)
        GbFlagState.OK -> Triple(Ink.TILE_OK, Ink.BLUE_EDGE, Ink.DIM2)
    }
    Box(Modifier.at(x, y).size(tw.dp, g.TILE_H.dp).plate(fill, line, g.TILE_R)) {
        ScanText(ScannerWords.gbBit(f.bit), Type.COUNTER_LABEL, Ink.MUTE, Modifier.at(g.PAD_X, g.PAD_Y))
        ScanText(f.fid, Type.TILE_ADDR, Ink.FAINT, Modifier.endAt(tw - g.PAD_X, g.PAD_Y))
        ScanText(f.name, Type.CELL_SMALL, Ink.WHITE, Modifier.at(g.PAD_X, g.NAME_Y + g.PAD_Y - g.NAME_LIFT), maxWidth = tw - 2 * g.PAD_X)
        ScanText(f.word, Type.CELL, word, Modifier.at(g.PAD_X, g.STATE_Y))
        f.raw?.let { ScanText(ScannerWords.gbCode(it), Type.TILE_ADDR, Ink.FAINT, Modifier.endAt(tw - g.PAD_X, g.STATE_Y + g.CODE_DROP)) }
    }
}
