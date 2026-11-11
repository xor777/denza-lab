package dev.denza.apps.core

import dev.denza.apps.R
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [AppText]'s words in a unit test, which has no `Resources`: `R.string` by parsing the file the
 * APK's strings compile from, so a function that asks [AppText] answers what it answered as a
 * literal. [AppText] finds this class by name, because no test may have to install anything: the
 * test files share no base class, and the suite must not move when a string does.
 *
 * `res/values/strings.xml` is the default, Russian. For English it reads `res/values-en/strings.xml`
 * first and falls back as `Resources` would: a name that file leaves out comes from the default, which
 * is what `translatable="false"` does. `R` maps the id to its name, by reflection.
 *
 * The no-argument constructor is the one [AppText] finds by name.
 */
internal class StringsXmlSource(private val language: AppLanguage = AppLanguage.RUSSIAN) : AppText.Source {
    private val default by lazy { StringsXml.parse(repoFile(PATH).readText()) }
    private val own by lazy {
        if (language == AppLanguage.ENGLISH) StringsXml.parse(repoFile(PATH_EN).readText()) else null
    }

    override fun string(id: Int): String {
        lookups.incrementAndGet()
        val name = nameOf("string", id)
        return own?.strings?.get(name) ?: default.strings[name] ?: error("R.string.$name is not in $PATH")
    }

    override fun quantity(id: Int, count: Int): String {
        val name = nameOf("plurals", id)
        val xml = own?.takeIf { name in it.plurals } ?: default
        return xml.quantity(name, count, language)
    }

    private fun nameOf(type: String, id: Int): String =
        Class.forName("${R::class.java.name}$$type").fields
            .firstOrNull { it.getInt(null) == id }?.name
            ?: error("no R.$type has the id $id")

    private fun repoFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = File(dir, path)
            if (file.isFile) return file
            dir = dir.parentFile
        }
        error("$path not found above ${System.getProperty("user.dir")}")
    }

    companion object {
        private const val PATH = "apps/denza-apps/src/main/res/values/strings.xml"
        private const val PATH_EN = "apps/denza-apps/src/main/res/values-en/strings.xml"

        /** How many times [AppText] has come here for a string, which on a car is `Resources`. */
        val lookups = AtomicInteger()
    }
}

/** A `strings.xml`, its text read the way aapt2 compiles it. */
internal class StringsXml(
    val strings: Map<String, String>,
    val plurals: Map<String, Map<String, String>>,
) {
    /** The form Android picks in [language]; a form the file leaves out falls back to `other`. */
    fun quantity(name: String, count: Int, language: AppLanguage = AppLanguage.RUSSIAN): String {
        val forms = plurals[name] ?: error("R.plurals.$name is not in strings.xml")
        val form = when (language) {
            AppLanguage.RUSSIAN -> russianForm(count)
            AppLanguage.ENGLISH -> englishForm(count)
        }
        return forms[form] ?: forms["other"]
            ?: error("R.plurals.$name has neither $form nor other")
    }

    /** CLDR's English for a whole number: exactly one is `one`, and 21 is not. */
    private fun englishForm(count: Int): String = if (count == 1) "one" else "other"

    private fun russianForm(count: Int): String {
        val tens = count % 100
        return when {
            tens in 11..14 -> "many"
            count % 10 == 1 -> "one"
            count % 10 in 2..4 -> "few"
            else -> "many"
        }
    }

    companion object {
        fun parse(xml: String): StringsXml {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray()))
                .documentElement
            return StringsXml(
                strings = root.children("string")
                    .associate { it.getAttribute("name") to compiled(it.textContent) },
                plurals = root.children("plurals").associate { plural ->
                    plural.getAttribute("name") to plural.children("item")
                        .associate { it.getAttribute("quantity") to compiled(it.textContent) }
                },
            )
        }

        private fun Element.children(tag: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length).map(nodes::item).filterIsInstance<Element>()
                .filter { it.tagName == tag }
        }

        /**
         * aapt2's flattening, checked against aapt2 itself. Only ASCII whitespace is whitespace: the
         * ends are trimmed and a run collapses to one space unless double-quoted, so NBSP, U+202F,
         * U+2009 and U+3000 stay. A bare double quote only switches that, is dropped, and ends a run,
         * so `a "" b` keeps both spaces. A backslash escapes, and a trailing one is dropped.
         *
         * aapt2 would compile a leading `@` or `?` into a reference, which this cannot resolve.
         */
        internal fun compiled(raw: String): String {
            val text = raw.trim(Char::isAsciiSpace)
            if (text.startsWith('@') || text.startsWith('?')) {
                error("\"$text\" is a reference, which the unit-test seam cannot resolve")
            }
            val out = StringBuilder()
            var quoted = false
            var inSpace = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isAsciiSpace() && !quoted) {
                    if (!inSpace) out.append(' ')
                    inSpace = true
                    i += 1
                    continue
                }
                inSpace = false
                i += 1
                when (c) {
                    '"' -> quoted = !quoted
                    '\\' -> if (i < text.length) {
                        when (val escaped = text[i]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'u' -> {
                                out.append(text.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(escaped)
                        }
                        i += 1
                    }
                    else -> out.append(c)
                }
            }
            return out.toString()
        }
    }
}

/** What C's `isspace` takes in the ASCII range, which is all aapt2 asks it about. */
private fun Char.isAsciiSpace(): Boolean = this == ' ' || this in '\t'..'\r'
