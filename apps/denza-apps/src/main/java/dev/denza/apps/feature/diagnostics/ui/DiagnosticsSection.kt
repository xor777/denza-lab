package dev.denza.apps.feature.diagnostics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.denza.apps.feature.diagnostics.scan.DiagnosticsSession
import dev.denza.apps.feature.diagnostics.scan.of

/**
 * «Сервис → Диагностика»: the scanner over the main process's session. The session runs while the
 * section stands on a started screen - the hub claim, the helper, the autoscan when the last one is
 * old - and stops when it closes or the screen stops (architecture, 6).
 */
@Composable
internal fun DiagnosticsSection(onClose: () -> Unit) {
    val context = LocalContext.current
    val session = remember { DiagnosticsSession.of(context) }
    // A stop forgets what the screens asked of the session; each start counts a run, and the
    // screens' asks are keyed on it, so they ask again on a section that comes back (LocalScannerRun).
    var run by remember { mutableIntStateOf(0) }
    LifecycleStartEffect(session) {
        session.start()
        run++
        onStopOrDispose { session.stop() }
    }
    val model by session.model.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(ink(ScannerSpec.Ink.GROUND)).windowInsetsPadding(WindowInsets.safeDrawing)) {
            CompositionLocalProvider(LocalScannerRun provides run) { DiagnosticsScreen(model, session, onClose) }
        }
    }
}
