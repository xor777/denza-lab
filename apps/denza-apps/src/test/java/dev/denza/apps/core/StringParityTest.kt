package dev.denza.apps.core

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Comment
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text

/**
 * `values-en/strings.xml` says what `values/strings.xml` says: the same keys, the same placeholders,
 * the plural forms its language needs, no Cyrillic, and every firmware quote marked as one.
 *
 * Both files are read as [StringsXml] reads them, the way aapt2 compiles them. This asks nothing of
 * `AppText`, which stays on Russian.
 */
class StringParityTest {

    private val russian = Resources.read("values")
    private val english = Resources.read("values-en")

    @Test
    fun bothFilesHoldTheSameTranslatableKeys() {
        assertEquals(russian.translatableStrings, english.translatableStrings)
        assertEquals(russian.translatablePlurals, english.translatablePlurals)
        assertTrue(
            "values-en translates what is marked translatable=\"false\": ${english.untranslatable}",
            english.names.intersect(russian.untranslatable).isEmpty(),
        )
    }

    @Test
    fun placeholdersMatchInIndexAndType() {
        for (name in russian.translatableStrings) {
            assertEquals(
                "R.string.$name",
                placeholders(russian.xml.strings.getValue(name)),
                placeholders(english.xml.strings.getValue(name)),
            )
        }
        for (name in russian.translatablePlurals) {
            val forms = russian.xml.plurals.getValue(name).values + english.xml.plurals.getValue(name).values
            assertEquals("R.plurals.$name", 1, forms.map(::placeholders).toSet().size)
        }
    }

    @Test
    fun aPlaceholderCarriesAnIndexAndAPercentSignIsDoubled() {
        for ((name, text) in english.xml.strings + english.xml.plurals.flatMap { (name, forms) ->
            forms.map { (quantity, text) -> "$name/$quantity" to text }
        }) {
            val left = PLACEHOLDER.replace(text, "").replace("%%", "")
            assertTrue("$name has a bare %: «$text»", '%' !in left)
        }
    }

    @Test
    fun pluralsHaveTheFormsTheirLanguageNeeds() {
        for ((name, forms) in russian.xml.plurals) {
            assertEquals("values/ R.plurals.$name", setOf("one", "few", "many", "other"), forms.keys)
        }
        for ((name, forms) in english.xml.plurals) {
            assertEquals("values-en/ R.plurals.$name", setOf("one", "other"), forms.keys)
        }
    }

    @Test
    fun englishHoldsNoCyrillic() {
        val texts = english.xml.strings.toList() + english.xml.plurals.flatMap { (name, forms) ->
            forms.map { (quantity, text) -> "$name/$quantity" to text }
        }
        for ((name, text) in texts) {
            assertTrue("$name: «$text»", text.none { it in 'Ѐ'..'ӿ' })
        }
    }

    @Test
    fun aFirmwareQuoteIsMarkedInBothLanguages() {
        assertTrue("the mark has gone from values/", "defaultapps_sheet_help" in russian.firmwareQuotes)
        assertEquals(russian.firmwareQuotes, english.firmwareQuotes)
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    private class Resources(
        val xml: StringsXml,
        val names: Set<String>,
        val untranslatable: Set<String>,
        val translatableStrings: Set<String>,
        val translatablePlurals: Set<String>,
        val firmwareQuotes: Set<String>,
    ) {
        companion object {
            fun read(directory: String): Resources {
                val text = repoFile("apps/denza-apps/src/main/res/$directory/strings.xml").readText()
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(ByteArrayInputStream(text.toByteArray()))
                    .documentElement
                val nodes = root.childNodes
                val elements = (0 until nodes.length).map(nodes::item).filterIsInstance<Element>()
                val untranslatable = elements
                    .filter { it.getAttribute("translatable") == "false" }
                    .map { it.getAttribute("name") }.toSet()
                fun translatable(tag: String) =
                    elements.filter { it.tagName == tag }.map { it.getAttribute("name") }.toSet() - untranslatable
                return Resources(
                    xml = StringsXml.parse(text),
                    names = elements.map { it.getAttribute("name") }.toSet(),
                    untranslatable = untranslatable,
                    translatableStrings = translatable("string"),
                    translatablePlurals = translatable("plurals"),
                    firmwareQuotes = elements.filter(::isMarked).map { it.getAttribute("name") }.toSet(),
                )
            }

            /** A comment right above the element, opening with either mark: recorded, or a guess. */
            private fun isMarked(element: Element): Boolean {
                var node: Node? = element.previousSibling
                while (node != null) {
                    when {
                        node is Comment -> {
                            val comment = node.data.trim()
                            if (MARKS.any(comment::startsWith)) return true
                        }
                        node is Text && node.data.isBlank() -> Unit
                        else -> return false
                    }
                    node = node.previousSibling
                }
                return false
            }

            private fun repoFile(path: String): File {
                var dir: File? = File(System.getProperty("user.dir")).absoluteFile
                while (dir != null) {
                    val file = File(dir, path)
                    if (file.isFile) return file
                    dir = dir.parentFile
                }
                error("$path not found above ${System.getProperty("user.dir")}")
            }
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""%\d+\$[sdf]""")

        /** `FIRMWARE:` is a best guess the docs do not record; the other cites what they do. */
        val MARKS = listOf("Quotes the firmware", "FIRMWARE:")
    }
}
