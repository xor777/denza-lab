package dev.denza.apps.core

import java.util.Locale

/**
 * The language of the app's own words, chosen in «Сервис» and nowhere else. It has nothing to do with
 * the car's language, which `SystemLanguage` reads and the «Язык системы» tile opens the picker for.
 *
 * Russian is the default and is not stored: an install nobody touched has no value, on any car.
 */
enum class AppLanguage(internal val stored: String, languageTag: String) {
    RUSSIAN("ru", "ru-RU"),
    ENGLISH("en", "en"),
    ;

    val locale: Locale = Locale.forLanguageTag(languageTag)

    /** Read once per language: the mark is asked for wherever a figure changes. */
    internal val decimalMark: Char by lazy { AppDecimal.separator(locale) }

    companion object {
        /** What a stored value means. Nothing stored, or anything this build does not know, is Russian. */
        internal fun ofStored(value: String?): AppLanguage =
            entries.firstOrNull { it.stored == value } ?: RUSSIAN
    }
}

/**
 * What a tap on the language row does, and in what order, with no Android in it.
 *
 * The words are read once per process and many surfaces keep them, so a new language means a new
 * process: nothing here tries to change one in place (docs/system-language.md, "The language switch").
 */
internal object AppLanguageSwitch {
    interface Steps {
        /** Stores the choice durably. False leaves the stored value as it was. */
        fun write(language: AppLanguage): Boolean

        /** Ends the `:picker` process, which read the old value when it started. */
        fun endPicker()

        fun relaunch()

        fun endSelf()
    }

    enum class Result { SAME_LANGUAGE, NOT_WRITTEN, RESTARTING }

    /**
     * The write comes first and decides. If it fails nothing has happened: no process ends, and the
     * row still shows the running language, which is the only thing it reads.
     */
    fun choose(current: AppLanguage, chosen: AppLanguage, steps: Steps): Result {
        if (chosen == current) return Result.SAME_LANGUAGE
        if (!steps.write(chosen)) return Result.NOT_WRITTEN
        steps.endPicker()
        steps.relaunch()
        steps.endSelf()
        return Result.RESTARTING
    }
}
