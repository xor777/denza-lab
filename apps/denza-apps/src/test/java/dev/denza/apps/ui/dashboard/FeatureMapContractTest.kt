package dev.denza.apps.ui.dashboard

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

        val shown = Regex("""name = "([^"]+)"""")
            .findAll(File(main, "ui/dashboard/DashboardTiles.kt").readText())
            .map { it.groupValues[1] }
            .toList()
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
            declaring(owner).any { declares(it, member) }
        }
        CONSTANT.matches(token) -> sources.values.any { Regex("""\b$token\b""").containsMatchIn(it) }
        IDENTIFIER.matches(token) -> declared(token) || quoted(token)
        LITERAL.matches(token) -> quoted(token)
        // Commands, hex values, prose in code type: nothing to resolve.
        else -> true
    }

    private fun pathOf(token: String): File =
        if (ROOTS.any { token.startsWith(it) }) File(repo, token) else File(main, token)

    private fun declaring(owner: String): List<String> {
        val declaration = Regex("""\b(class|object|interface|enum)\s+$owner\b""")
        return sources.values.filter { declaration.containsMatchIn(it) }
    }

    private fun declares(text: String, member: String): Boolean =
        if (CONSTANT.matches(member)) {
            Regex("""\b$member\b""").containsMatchIn(text)
        } else {
            Regex("""\b(fun|val|var)\s+(<[^>]+>\s+)?([\w.]+\.)?$member\b""").containsMatchIn(text) ||
                Regex("""[\w>\]]\s+$member\s*\(""").containsMatchIn(text)
        }

    /** A class, object, interface or function: Kotlin, Java, or the board's own JavaScript. */
    private fun declared(name: String): Boolean {
        val kotlin = Regex("""\b(class|object|interface|fun)\s+(<[^>]+>\s+)?([\w.]+\.)?$name\b""")
        val java = Regex("""\b(class|interface|enum)\s+$name\b""")
        val script = Regex("""\bfunction\s+$name\b""")
        return sources.values.any {
            kotlin.containsMatchIn(it) || java.containsMatchIn(it) || script.containsMatchIn(it)
        }
    }

    /** A preferences key or another literal, written in the sources as a string. */
    private fun quoted(token: String): Boolean = sources.values.any { "\"$token\"" in it }

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
