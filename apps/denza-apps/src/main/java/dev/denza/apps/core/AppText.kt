package dev.denza.apps.core

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * The app's own words, resolved in the app's own language and never in the car's.
 *
 * One holder for the whole process, because most of the app's words are made where no Context is in
 * scope: pure functions, constants, enums. `Application.attachBaseContext` installs it in both the
 * main and the `:picker` process. Everything else asks here: `stringResource` and
 * `getString(R.string…)` read the car's locale, and `CyrillicLiteralGuardTest` forbids them.
 *
 * The language is the stored choice ([AppLanguageStore]), Russian when there is none, and it stays
 * for the life of the process: changing it restarts the app. It does not change
 * `Locale.getDefault()`, which `SystemLanguage` reads as the car's language (docs/system-language.md,
 * "The app's own language").
 */
object AppText {
    /** Where the words come from: the app's resources, or in a unit test `strings.xml` itself. */
    internal interface Source {
        fun string(@StringRes id: Int): String

        fun quantity(@PluralsRes id: Int, count: Int): String
    }

    /** Unit tests have no `Resources`. This class exists only on their classpath. */
    private const val UNIT_TEST_SOURCE = "dev.denza.apps.core.StringsXmlSource"

    @Volatile
    private var source: Source? = null

    /** Holds what [s] has read: every drawn surface asks through it, some of them on every frame. */
    @Volatile
    private var words = WordMemo()

    /** What this process speaks. Until something installs a language, a unit test's Russian. */
    @Volatile
    var language: AppLanguage = AppLanguage.RUSSIAN
        private set

    fun install(base: Context) {
        val stored = AppLanguageStore.read(base)
        // An override holding the locale alone: a copy of the base would pin density, uiMode and size.
        val override = Configuration().apply { setLocale(stored.locale) }
        val resources = base.createConfigurationContext(override).resources
        install(
            object : Source {
                override fun string(id: Int): String = resources.getString(id)

                override fun quantity(id: Int, count: Int): String = resources.getQuantityString(id, count)
            },
            stored,
        )
    }

    /** The one place the language and its words change together; a unit test comes in here too. */
    internal fun install(source: Source, language: AppLanguage) {
        this.language = language
        this.source = source
        words = WordMemo()
    }

    /** The language these words are in, for a service that answers in the language it is asked. */
    val locale: Locale get() = language.locale

    /**
     * `getString` with nothing to fill: the text as it is, `%` included.
     *
     * This is the one that may be called from a draw loop. The first call for an id reads
     * `Resources`, each later one is an array read and allocates nothing, which is also why it is an
     * overload: a call to the `vararg` form with no arguments would allocate an empty array per call.
     * A language change restarts the process, so the memo cannot go stale.
     */
    @JvmStatic
    fun s(@StringRes id: Int): String =
        words.get(id) ?: source().string(id).also { words.put(id, it) }

    /**
     * `getString(id, args)`. This formats on every call, so a figure drawn every frame keeps the
     * result until the figure changes (`ContourFigures`, `StripReadings.Printed`).
     */
    @JvmStatic
    fun s(@StringRes id: Int, vararg args: Any?): String = format(s(id), args)

    /** `getQuantityString`: [count] picks the form, [args] fill it, so a count is usually in both. */
    @JvmStatic
    fun p(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
        format(source().quantity(id, count), args)

    /** In the text's own locale, as `Resources` would: `%.1f` writes a decimal comma in Russian. */
    internal fun format(text: String, args: Array<out Any?>): String =
        if (args.isEmpty()) text else String.format(locale, text, *args)

    private fun source(): Source = source ?: unitTestSource().also { source = it }

    private fun unitTestSource(): Source = try {
        Class.forName(UNIT_TEST_SOURCE).getDeclaredConstructor().newInstance() as Source
    } catch (absent: ClassNotFoundException) {
        throw IllegalStateException("AppText used before Application.attachBaseContext", absent)
    }
}

/**
 * Text by resource id, with no lock and no boxing on the way to it: a `HashMap<Int, String>` would
 * make an `Integer` per lookup, on a view drawn over the vehicle's instruments.
 *
 * Direct-mapped, because string ids are consecutive within their type, so the low bits are the entry
 * and the size only has to exceed the app's strings. Two ids that share a slot put each other out
 * and cost a second read, never a wrong word: the id is kept beside its text. Bounded, then, whatever
 * is asked. A race between two writers loses a slot's entry, which is the same second read.
 */
internal class WordMemo {
    private class Word(val id: Int, val text: String)

    private val slots = AtomicReferenceArray<Word?>(SIZE)

    fun get(id: Int): String? {
        val word = slots.get(id and MASK) ?: return null
        return if (word.id == id) word.text else null
    }

    fun put(id: Int, text: String) {
        slots.set(id and MASK, Word(id, text))
    }

    companion object {
        /** A power of two past the merged `R.string`, libraries included. */
        const val SIZE = 4096
        private const val MASK = SIZE - 1
    }
}
