package dev.denza.apps.ui.dashboard

import dev.denza.apps.DenzaUiState
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/feature-map.md` names, for every tile, the code a change to it has to visit, and this
 * keeps the names true.
 *
 * The map exists because agents spent 40 to 160 tool calls per task finding where a tile lives:
 * «Экран водителя» is `TileId.CLUSTER` and spans two packages, and the «Облако» tile touched
 * twenty-one files. A map that drifts is worse than none, so every backticked token in it must
 * still resolve: a path must exist, `Class.member` must be declared in the file that declares
 * the class, a bare name must be declared somewhere, a test must exist, a fixture key must be in
 * the debug fixtures. A renamed symbol fails here instead of sending the next agent to look for
 * something that is gone. Every tile must have its section, under the name the dashboard shows.
 */
class FeatureMapContractTest {

    @Test
    fun everyTileHasASectionUnderItsOwnName() {
        val headings = map.lines().filter { it.startsWith("### ") }
        val missing = TileId.entries.filter { id -> headings.none { "`${id.name}`" in it } }
        assertTrue("tiles with no section in docs/feature-map.md: $missing", missing.isEmpty())

        // The names the dashboard shows, asked of the dashboard: a pattern over its source found
        // nothing to check the day the names stopped being written `name = "…"`.
        val shown = DashboardTiles.of(DenzaUiState()).map { it.name }
        val unnamed = shown.filter { name -> headings.none { "«$name»" in it } }
        assertTrue("tile names the map does not use: $unnamed", unnamed.isEmpty())
    }

    @Test
    fun everyBacktickedNameStillResolves() {
        val prose = map.replace(Regex("(?s)```.*?```"), "")
        val unresolved = Regex("`([^`]+)`").findAll(prose)
            .map { it.groupValues[1] }
            .distinct()
            .filterNot(::resolves)
            .toList()
        assertTrue(
            "names in docs/feature-map.md that no longer resolve: $unresolved",
            unresolved.isEmpty(),
        )
    }

    private fun resolves(token: String): Boolean = when {
        token in fixtures -> true
        '/' in token -> pathOf(token).exists()
        FILE_NAME.matches(token) -> File(repo, token).exists() || fileNames.contains(token)
        TEST_CLASS.matches(token) -> testClasses.contains(token)
        MEMBER.matches(token) -> {
            val (owner, member) = token.split('.')
            owners[owner].orEmpty().any { source ->
                member in if (CONSTANT.matches(member)) source.words else source.members
            }
        }
        CONSTANT.matches(token) -> token in words
        IDENTIFIER.matches(token) -> token in declared || token in quoted
        LITERAL.matches(token) -> token in quoted
        // Commands, hex values, prose in code type: nothing to resolve.
        else -> true
    }

    private fun pathOf(token: String): File =
        if (ROOTS.any { token.startsWith(it) }) File(repo, token) else File(main, token)

    private companion object {
        val ROOTS = listOf("apps/", "docs/", "tools/", "libraries/", "research/", "experiments/")
        val FILE_NAME = Regex("""[\w.-]+\.(kt|java|md|js|json|xml|py|html)""")
        val TEST_CLASS = Regex("""[A-Z]\w*Test""")
        val MEMBER = Regex("""[A-Z]\w*\.\w+""")
        val CONSTANT = Regex("""[A-Z][A-Z0-9_]*""")
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
        val LITERAL = Regex("""[a-z0-9_.]+""")

        val repo: File = generateSequence(
            File(requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }),
        ) { it.parentFile }
            .firstOrNull { File(it, "docs/feature-map.md").isFile }
            ?: error("docs/feature-map.md is not above ${System.getProperty("user.dir")}")

        val main = File(repo, "apps/denza-apps/src/main/java/dev/denza/apps")
        val map: String = File(repo, "docs/feature-map.md").readText()

        /**
         * Every Kotlin and Java source of the app and the shared library, tests included, and the
         * board's renderer, whose function names the map uses for what a tile looks like.
         */
        val sources: Map<File, String> = (
            listOf(
                File(repo, "apps/denza-apps/src"),
                File(repo, "libraries/dishare-bridge/src"),
            ).flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .toList()
            } + File(repo, "tools/design-canvas/luminofor/luminofor.js")
            ).associateWith { it.readText() }

        /** One source, indexed once: every word in it, and every member name it declares. */
        class Indexed(val words: Set<String>, val members: Set<String>)

        /**
         * The names a source declares after one of [keywords]: the last segment of `fun Foo.bar`
         * and every segment before it, since the receiver is optional to the pattern too. The name
         * is read ahead rather than consumed, so `enum class Foo` and `fun interface Foo` still
         * show the keyword in front of `Foo` to the next match.
         */
        fun declarations(text: String, keywords: String): Sequence<String> =
            Regex("""\b(?:$keywords)\s+(?:<[^>]+>\s+)?(?=([\w.]+))""").findAll(text)
                .flatMap { it.groupValues[1].split('.').asSequence() }
                .filter { it.isNotEmpty() }

        private val WORD = Regex("""\w+""")

        /**
         * The sources, indexed in one pass: a word set, the declared names, the quoted literals and
         * which files declare which types. Until 2026-10-08 every backticked name compiled its own
         * patterns and ran them over every source, which was 39 of the suite's 44 seconds.
         */
        val indexed: Map<File, Indexed> = sources.mapValues { (_, text) ->
            Indexed(
                words = WORD.findAll(text).map { it.value }.toSet(),
                members = (
                    declarations(text, "fun|val|var") +
                        Regex("""[\w>\]]\s+(\w+)(?=\s*\()""").findAll(text).map { it.groupValues[1] }
                    ).toSet(),
            )
        }

        val words: Set<String> = indexed.values.flatMapTo(HashSet()) { it.words }

        /** A class, object, interface or function: Kotlin, Java, or the board's own JavaScript. */
        val declared: Set<String> = sources.values.flatMapTo(HashSet()) { text ->
            declarations(text, "class|object|interface|fun") +
                Regex("""\b(?:class|interface|enum|function)\s+(?=(\w+))""").findAll(text).map { it.groupValues[1] }
        }

        /** A preferences key or another literal, written in the sources as a string. */
        val quoted: Set<String> = sources.values.flatMapTo(HashSet()) { text ->
            Regex("""(?<=")[A-Za-z0-9_.]+(?=")""").findAll(text).map { it.value }
        }

        /** Which files declare a type of each name. */
        val owners: Map<String, List<Indexed>> = sources.entries
            .flatMap { (file, text) ->
                Regex("""\b(?:class|object|interface|enum)\s+(?=(\w+))""").findAll(text)
                    .map { it.groupValues[1] to indexed.getValue(file) }
                    .toList()
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, files) -> files.distinct() }

        /** A file named on its own, without its directory: somewhere in the app's sources. */
        val fileNames: Set<String> = sources.keys.map { it.name }.toSet()

        /** Every test class in the products and the library, not only this module's. */
        val testClasses: Set<String> = listOf("apps", "libraries")
            .flatMap { File(repo, it).listFiles().orEmpty().toList() }
            .map { File(it, "src/test") }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { it.nameWithoutExtension }
            .toSet()

        /** The top-level keys of the debug build's Luminofor fixtures, one space in. */
        val fixtures: Set<String> =
            Regex("""^ "([a-z0-9-]+)": [\[{]""", RegexOption.MULTILINE)
                .findAll(File(repo, "apps/denza-apps/src/debug/assets/luminofor/fixtures.json").readText())
                .map { it.groupValues[1] }
                .toSet()
    }
}
