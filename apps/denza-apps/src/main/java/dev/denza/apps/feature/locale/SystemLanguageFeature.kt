package dev.denza.apps.feature.locale

import android.content.Context
import dev.denza.apps.core.SliceFeature
import dev.denza.apps.core.SliceHandle

/**
 * The «Язык системы» tile's feature: the language the car speaks, and the door to its own list.
 *
 * No coordinator and no claim: the reading is [java.util.Locale.getDefault], which cannot fail and
 * cannot be refused, and the car is asked nothing. The machinery the old per-application override
 * needed - a permission granted over ADB, a running flag, an ABA-safe compare, two shapes of
 * failure - all belonged to writing somebody else's locale, and nothing here writes anything.
 *
 * Its code lived in `DenzaAppRepository` until 2026-10-09; it moved as it was.
 */
class SystemLanguageFeature(
    private val slice: SliceHandle<SystemLanguageSnapshot>,
    private val context: () -> Context?,
) : SliceFeature<SystemLanguageSnapshot> {

    override fun read(context: Context): SystemLanguageSnapshot = SystemLanguage.read()

    /** What language the car is speaking, read again: «Сервис» asks as it opens. */
    fun refresh() {
        slice.mark("language")
    }

    /**
     * Hand the language over to the car's own list.
     *
     * The whole feature. This app does not set the language, does not mirror what was chosen and
     * does not need to be told afterwards: the car applies it to the system locale, every process
     * is reconfigured, and this one comes back through a full read of the state like any other.
     */
    fun open() {
        val context = context() ?: return
        SystemLanguage.open(context)
    }
}
