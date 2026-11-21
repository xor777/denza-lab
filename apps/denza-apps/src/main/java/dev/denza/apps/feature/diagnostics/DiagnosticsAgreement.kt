package dev.denza.apps.feature.diagnostics

import android.annotation.SuppressLint
import android.content.Context

/**
 * The window in front of «Диагностика» (architecture, 1): what the section is and is not, a box to
 * tick and «ОК». The driver agrees once per install - not per language - and the window never shows
 * again; until then nothing of the section runs.
 *
 * Stored in the feature's own file under its own key. The names are spelled out in the test: a
 * renamed key would ask every driver again after an update.
 */
internal object DiagnosticsAgreement {
    private const val PREFS = "denza_diagnostics"
    private const val AGREED = "agreed"

    /** Whether the driver has ticked the box and pressed «ОК»: false on a fresh install. */
    fun isAgreed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AGREED, false)

    /** «ОК» with the box ticked: for good. Nothing takes it back. */
    // Keep the persistent write boundary explicit for feature recovery.
    @SuppressLint("UseKtx")
    fun agree(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AGREED, true)
            .apply()
    }
}

/**
 * What a way into the section opens. Every way in - the «Сервис» row, the service panel coming back
 * on its diagnostics page after the activity or the process was recreated - asks this one question,
 * and only [SECTION] composes the section, which is what starts its session, helper and reads.
 */
internal enum class DiagnosticsDoor {
    AGREEMENT,
    SECTION,
    ;

    companion object {
        fun of(agreed: Boolean): DiagnosticsDoor = if (agreed) SECTION else AGREEMENT
    }
}

/**
 * The window's one control: the box, unticked when the window opens. «ОК» answers only while it is
 * ticked; unticking takes it back. A tick is the window's own and stores nothing - only [confirm]
 * agrees.
 */
internal data class AgreementBox(val ticked: Boolean = false) {
    val okEnabled: Boolean get() = ticked

    fun toggled(): AgreementBox = copy(ticked = !ticked)

    /** «ОК» pressed: whether that agrees. A press on the inactive button agrees to nothing. */
    fun confirm(): Boolean = okEnabled
}
