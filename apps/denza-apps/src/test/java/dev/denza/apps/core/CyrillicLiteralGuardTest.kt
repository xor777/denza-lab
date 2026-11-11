package dev.denza.apps.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the app's Cyrillic to a list, so that translating it can go file by file and nothing new
 * arrives behind it. A driver's word belongs in `res/values/strings.xml`, read through [AppText]:
 * docs/system-language.md, "The app's own language".
 *
 * Each file on the list may hold exactly as many Cyrillic literals as it says. A new file or a
 * higher count fails, and so does a lower one, until the entry comes down with it: otherwise the
 * room left behind would take the next literal. An entry that reaches zero is deleted. Comments
 * never count, which is why this lexes the sources instead of searching them.
 */
class CyrillicLiteralGuardTest {
    @Test
    fun everyCyrillicLiteralIsOnTheList() {
        val found = scanned.filterValues { it.literals.isNotEmpty() }.mapValues { it.value.literals }
        assertEquals(emptyList<String>(), violations(found, ALLOWED, PARKED))
    }

    @Test
    fun onlyAppTextReadsStringResources() {
        val calls = scanned.filterKeys { it != APP_TEXT }.flatMap { (file, lexed) ->
            forbiddenCalls(lexed.code).map { "$file: $it reads the car's locale; use AppText" }
        }
        assertEquals(emptyList<String>(), calls)
    }

    /**
     * The seam returns Russian, so `YandexGuidanceParserTest` would stay green after a wrong
     * extraction, and the failure would show only in an English build. Hence a ban and not a review.
     */
    @Test
    fun externalTextIsNeverExtracted() {
        val uses = scanned.filterKeys { it in EXTERNAL }.flatMap { (file, lexed) ->
            externalTextUses(lexed.code).map { "$file: $it; these words match another program's text" }
        }
        assertEquals(emptyList<String>(), uses)
        assertEquals(EXTERNAL.keys, EXTERNAL.keys.filter { it in scanned }.toSet())
    }

    /** An empty scan finds no offenders either: a moved source root must fail here, not pass. */
    @Test
    fun theScanReachesEveryRoot() {
        val files = scanned.keys
        assertTrue("only ${files.size} files", files.size > 400)
        assertTrue(files.any { it.startsWith("libraries/hud-link/") })
        assertTrue(files.any { it.startsWith("libraries/dishare-bridge/") })
        assertTrue("feature/locale/SystemLanguage.kt" in files)
    }

    @Test
    fun theRatchetOnlyGoesDown() {
        val allowed = mapOf("a.kt" to Allowed(2, Reason.PENDING))
        fun check(vararg found: Pair<String, Int>) = violations(
            found.associate { (file, count) -> file to List(count) { CyrillicLiteral(it + 1, "а") } },
            allowed,
            "parked/",
        )

        assertEquals(emptyList<String>(), check("a.kt" to 2))
        assertTrue(check("a.kt" to 2, "b.kt" to 1).single().startsWith("b.kt: new file"))
        assertTrue(check("a.kt" to 3).single().startsWith("a.kt: 3 literals, the list allows 2"))
        assertTrue(check("a.kt" to 1).single().startsWith("a.kt: 1 literals, the list still says 2"))
        assertTrue(check().single().startsWith("a.kt: on the list with no literals"))
        assertEquals(emptyList<String>(), check("a.kt" to 2, "parked/x/y.kt" to 99))
    }

    /** A file with an entry keeps its count, even inside a parked directory. */
    @Test
    fun anEntryHoldsInsideAParkedDirectory() {
        fun check(count: Int) = violations(
            mapOf("parked/held.kt" to List(count) { CyrillicLiteral(it + 1, "а") }),
            mapOf("parked/held.kt" to Allowed(1, Reason.EXTERNAL)),
            "parked/",
        )

        assertEquals(emptyList<String>(), check(1))
        assertTrue(check(2).single().startsWith("parked/held.kt: 2 literals"))
        assertTrue(check(0).single().startsWith("parked/held.kt: 0 literals"))
    }

    @Test
    fun forbiddenCallsAreFoundInCodeOnly() {
        assertEquals(1, calls("val a = stringResource(R.string.x)").size)
        assertEquals(1, calls("val a = getString(R.string.x)").size)
        assertEquals(1, calls("val a = context.getString( R.string.x )").size)
        assertEquals(1, calls("val a = \"\${stringResource(R.string.x)}\"").size)
        assertEquals(0, calls("// stringResource(\n/* getString(R.string.x) */").size)
        assertEquals(0, calls("val a = \"stringResource(\"").size)
        assertEquals(0, calls("import androidx.compose.ui.res.stringResource").size)
        assertEquals(0, calls("val a = preferences.getString(KEY, null)").size)
    }

    @Test
    fun everyWayOfReadingAnIdDirectlyIsFound() {
        listOf(
            "val a = getText(R.string.x)",
            "view.setText(R.string.x)",
            "view.setText( R . string . x )",
            "val a = resources.getQuantityString(R.plurals.x, 2, 2)",
            "val a = resources.getQuantityText(R.plurals.x, 2)",
            "val a = pluralStringResource(R.plurals.x, 2)",
            "val a = getString(dev.denza.apps.R.string.x)",
            "val a = getText(android.R.string.ok)",
        ).forEach { assertEquals(it, 1, calls(it).size) }
    }

    /** Naming an id is not reading it, and the rest is what [AppText] and its callers do. */
    @Test
    fun whatIsNotAReadPasses() {
        listOf(
            "textRes = R.string.x,",
            "val a = AppText.s(R.string.x)",
            "val a = AppText.p(R.plurals.x, 2, 2)",
            "val a = node.getText()",
            "view.setText(label)",
            "view.text = AppText.s(textRes)",
            "val a = R.drawable.x",
        ).forEach { assertEquals(it, 0, calls(it).size) }
    }

    @Test
    fun externalFilesMayNotReachTheAppsWords() {
        fun uses(source: String) = externalTextUses(SourceLexer(source, kotlin = true).lex().code)

        assertEquals(1, uses("import dev.denza.apps.core.AppText").size)
        assertEquals(1, uses("val a = AppText.s(id)").size)
        assertEquals(1, uses("val a = R.string.x").size)
        assertEquals(1, uses("val a = dev.denza.apps.R.plurals.x").size)
        assertEquals(0, uses("// AppText and R.string stay out of here\n/* AppText */").size)
        assertEquals(0, uses("val a = \"AppText R.string\"").size)
        assertEquals(0, uses("import dev.denza.apps.R\nval a = R.drawable.x").size)
    }

    @Test
    fun commentsNeverCount() {
        assertEquals(emptyList<String>(), kotlin("// ё\n/* ё */\n/** ё */\nval x = 1"))
        assertEquals(listOf("г"), kotlin("/* а /* б */ в */ val x = \"г\""))
        assertEquals(listOf("г"), kotlin("// \" а\nval x = \"г\""))
        assertEquals(listOf("г"), kotlin("/* ' */ val x = \"г\" // '"))
    }

    /** Java's block comments do not nest, so the first close ends this one. */
    @Test
    fun javaIsItsOwnLanguage() {
        assertEquals(listOf("г"), java("/* а /* б */ String x = \"г\"; /* в */"))
        assertEquals(listOf("\${ё}"), java("String x = \"\${ё}\";"))
        assertEquals(listOf("\nда\" нет"), java("String x = \"\"\"\nда\\\" нет\"\"\";"))
    }

    @Test
    fun stringsAndEscapes() {
        assertEquals(listOf("да"), kotlin("val x = \"да\""))
        assertEquals(listOf("\"да"), kotlin("val x = \"\\\"да\""))
        assertEquals(listOf("Ж"), kotlin("val x = \"\\u0416\""))
        assertEquals(listOf("/* ё"), kotlin("val x = \"yes // no\" + \"/* ё\".length"))
    }

    @Test
    fun rawStrings() {
        assertEquals(listOf("да\" нет"), kotlin(raw("да\" нет")))
        assertEquals(listOf("да\\"), kotlin(raw("да\\")))
        assertEquals(listOf("да\""), kotlin(raw("да\"")))
        assertEquals(listOf(" да "), kotlin(raw("\$a да \${b}")))
        assertEquals(listOf("\nда\n"), kotlin(raw("\nда\n")))
    }

    /** With `$$"…"` a single dollar is text, and a template takes two. */
    @Test
    fun multiDollarStrings() {
        assertEquals(listOf("\$да"), kotlin("val x = \$\$\"\$да\""))
        assertEquals(listOf("\$да"), kotlin("val x = \$\$\"\"\"\$да\"\"\""))
        assertEquals(listOf("\${да}"), kotlin("val x = \$\$\"\${да}\""))
        assertEquals(emptyList<String>(), kotlin("val x = \$\$\"\$\$имя\""))
        assertEquals(emptyList<String>(), kotlin("val x = \$\$\"\$\$\$имя\""))
        assertEquals(listOf(" да"), kotlin("val x = \$\$\"\$\$имя да\""))
        assertEquals(listOf("да", " нет"), kotlin("val x = \$\$\"\$\${f(\"да\")} нет\""))
        assertEquals(listOf("\$\$да"), kotlin("val x = \$\$\$\"\$\$да\""))
        assertEquals(listOf("да"), kotlin("val x = \$\$\"\$\$`имя`\"; val y = \"да\""))
    }

    /** A literal counts once, and Cyrillic inside `${}` counts for the inner literal alone. */
    @Test
    fun templates() {
        assertEquals(listOf("да", " нет"), kotlin("val x = \"\${f(\"да\")} нет\""))
        assertEquals(listOf("да"), kotlin("val x = \"\${f(\"да\")}\""))
        assertEquals(listOf("да"), kotlin("val x = \"\${f(\"\${g(\"да\")}\")}\""))
        assertEquals(emptyList<String>(), kotlin("val x = \"\$имя\""))
        assertEquals(emptyList<String>(), kotlin("val x = \"\${имя}\""))
        assertEquals(listOf("\$имя"), kotlin("val x = \"\\\$имя\""))
        assertEquals(listOf("ё"), kotlin("val x = \"\${'ё'}\""))
        assertEquals(listOf("}ё"), kotlin("val x = \"\${ { \"}ё\" }() } x\""))
        assertEquals(emptyList<String>(), kotlin("val x = \"\${ /* } ё */ y } z\""))
        assertEquals(listOf("100\$ да"), kotlin("val x = \"100\$ да\""))
    }

    @Test
    fun charLiterals() {
        assertEquals(listOf("ё"), kotlin("val x = 'ё'"))
        assertEquals(listOf("ё"), kotlin("val x = '\\u0451'"))
        assertEquals(listOf("да"), kotlin("val x = '\"'; val y = \"да\""))
        assertEquals(listOf("да"), kotlin("val x = '\\''; val y = \"да\""))
        assertEquals(listOf("да"), kotlin("val x = '{'; val y = \"да\""))
        assertEquals(listOf("ё"), java("char x = 'ё';"))
    }

    @Test
    fun backtickedNamesAreNames() {
        assertEquals(emptyList<String>(), kotlin("fun `ё \" '`() = 1"))
    }

    /** Lost track of where it is, the lexer would miscount a whole file in silence. */
    @Test
    fun whatDoesNotLexFails() {
        listOf("val x = \"да\nval y = 1", "/* ё", "val x = 'ёё'", "val x = \"\${y", "val x = \"\"\"да").forEach {
            assertThrows(it, IllegalStateException::class.java) { kotlin(it) }
        }
    }

    private fun kotlin(source: String) = SourceLexer(source, kotlin = true).lex().literals.map { it.text }

    private fun java(source: String) = SourceLexer(source, kotlin = false).lex().literals.map { it.text }

    private fun calls(source: String) = forbiddenCalls(SourceLexer(source, kotlin = true).lex().code)

    private fun raw(body: String) = "val x = \"\"\"$body\"\"\""

    private companion object {
        /** Every source this guard reads, by the name its list entry uses. */
        val scanned: Map<String, Lexed> by lazy {
            val root = repoRoot()
            ROOTS.flatMap { path ->
                File(root, path).walkTopDown()
                    .filter { it.isFile && it.extension in setOf("kt", "java") }
                    .map { file ->
                        val repoPath = file.relativeTo(root).invariantSeparatorsPath
                        val lexed = try {
                            SourceLexer(file.readText(), kotlin = file.extension == "kt").lex()
                        } catch (failure: IllegalStateException) {
                            throw IllegalStateException("$repoPath: ${failure.message}", failure)
                        }
                        repoPath.removePrefix(APP_ROOT) to lexed
                    }
                    .toList()
            }.toMap()
        }

        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, ROOTS.first()).isDirectory) return dir
                dir = dir.parentFile
            }
            error("${ROOTS.first()} not found above ${System.getProperty("user.dir")}")
        }

        const val APP_ROOT = "apps/denza-apps/src/main/java/dev/denza/apps/"
        const val APP_TEXT = "core/AppText.kt"
        val ROOTS = listOf(
            "apps/denza-apps/src/main/java",
            "libraries/hud-link/src/main/java",
            "libraries/dishare-bridge/src/main/java",
        )
    }
}

internal enum class Reason {
    /** Text another program wrote, matched by this one. It must stay as it is: never extract it. */
    EXTERNAL,

    /** Read by the people who support the car, not by the driver as an interface. */
    REPORT,

    /** A log line or a developer's message. */
    LOG,

    /** Data, not an interface's words: autonyms, for instance, are never translated. */
    TABLE,

    /** A driver's word still to move to `strings.xml`. Falls to zero; then the entry goes. */
    PENDING,
}

internal class Allowed(val max: Int, val reason: Reason)

private fun allow(reason: Reason, vararg files: Pair<String, Int>): Map<String, Allowed> =
    files.associate { (file, max) -> file to Allowed(max, reason) }

/**
 * Parked until the owner accepts the section on the car (docs/vehicle-diagnostics-architecture.md,
 * 1), so it has no per-file counts, except `scan/BlockRecords.kt`, which is on [EXTERNAL].
 */
private const val PARKED = "feature/diagnostics/"

/**
 * DO NOT EXTRACT. These files match text another program or the research data wrote, which is
 * Russian whatever language this app speaks; a translated pattern would stop recognising it. The
 * guard fails if their code touches [AppText] or `R.string`.
 *
 * - `HudCaptureSource.java`: 1 literal, the strict shape of Yandex's remaining time.
 * - `HudGuidanceModels.kt`: Yandex's screen and notification text, plus 7 words of its own for the
 *   technical report (296, 325-328, 336, 343).
 * - `YandexNotificationGuidance.kt`: 1 of Yandex's literals (121) and 11 of its own, the canonical
 *   instructions (136-146), which reach only the technical report.
 * - `BlockRecords.kt`: 1 literal (135), matched against Russian DTC titles from the research JSON.
 *
 * An own word that has to be translated moves to another file first.
 */
private val EXTERNAL = allow(
    Reason.EXTERNAL,
    "feature/diagnostics/scan/BlockRecords.kt" to 1,
    "feature/hud/HudCaptureSource.java" to 1,
    "feature/hud/HudGuidanceModels.kt" to 47,
    "feature/hud/YandexNotificationGuidance.kt" to 12,
)

/**
 * Words a driver can reach. Where a file also holds report or log strings its count includes them:
 * when its driver's words are out, it moves to [REPORT] or [LOG] with what remains.
 * `HudProtocol.java` keeps `SummaryWords.RUSSIAN`: `:hud-link` is compiled into Navigator's dex,
 * where there is no `R`, and the YandexHUD module stays Russian by the owner's decision. Denza Apps
 * passes its own words to the overloads. These four leave this list when the module gets a language.
 */
private val PENDING = allow(
    Reason.PENDING,
    "libraries/hud-link/src/main/java/dev/denza/hudlink/HudProtocol.java" to 4,
)

private val REPORT = allow(
    Reason.REPORT,
    "StateRecomputes.kt" to 13,
    "SupportDiagnostics.kt" to 123,
    "adb/AdbProblem.kt" to 2,
    "feature/adb/AdbPortRestore.kt" to 12,
    "feature/adb/AdbRescueCoordinator.kt" to 4,
    "feature/adb/AdbRestoreReport.kt" to 43,
    "feature/cloud/CloudLinkController.kt" to 3,
    "feature/cloud/CloudLinkDiagnostics.kt" to 2,
    "feature/cloud/CloudLinkOperations.kt" to 10,
    "feature/cloud/CloudLinkProtocol.kt" to 8,
    "feature/cloud/CloudLinkReport.kt" to 35,
    "feature/cloud/CloudLinkRequest.kt" to 1,
    "feature/cloud/CloudLinkStatus.kt" to 3,
    "feature/cloud/CloudRegistrationFailure.kt" to 1,
    "feature/hud/HudHostOutput.java" to 7,
    "feature/hud/HudMapClusterSource.kt" to 2,
    "feature/hud/HudMapGrantPolicy.kt" to 8,
    "feature/hud/HudMapRunner.kt" to 1,
    "feature/hud/HudMapWindows.kt" to 2,
    "feature/hud/HudSomeIpRuntime.kt" to 8,
    "feature/media/MediaKeyDiagnostics.kt" to 8,
    "feature/media/MediaKeyExperiment.kt" to 2,
    "feature/navigation/NavigationCoordinator.kt" to 1,
    "feature/simulcast/SimulcastOverlayService.java" to 7,
    "feature/simulcast/SimulcastScreenDiagnostics.kt" to 29,
    "feature/split/SplitFirmwareReading.kt" to 10,
    "feature/split/SplitWorkJournal.kt" to 22,
    "feature/trip/SpectrumSource.kt" to 3,
)

private val LOG = allow(
    Reason.LOG,
    "feature/split/SplitCollapse.kt" to 24,
    "feature/split/SplitCommandProvider.kt" to 3,
    "feature/split/SplitCoordinatorCore.kt" to 6,
    "feature/split/SplitEdge.kt" to 7,
    "feature/split/SplitFirmwareSignals.kt" to 5,
    "feature/split/SplitGate.kt" to 6,
    "feature/split/SplitNativePickerAccessibilityService.kt" to 5,
    "feature/split/SplitNavReturn.kt" to 9,
    "feature/split/SplitOperations.kt" to 40,
    "feature/split/SplitOwnedScene.kt" to 10,
    "feature/split/SplitPickerActivity.kt" to 1,
    "feature/split/SplitResizeabilityController.kt" to 6,
    "feature/split/SplitSceneBuilder.kt" to 25,
    "feature/split/SplitSelect.kt" to 24,
    "feature/split/SplitSmartMultiController.kt" to 3,
    "feature/split/SplitTaskCommands.kt" to 9,
    "feature/split/SplitTeardown.kt" to 6,
    "feature/split/SplitWorld.kt" to 7,
    "feature/vehicle/ConsumptionJournal.kt" to 7,
    "feature/vehicle/JournalFile.kt" to 1,
    "feature/vehicle/TripJournal.kt" to 4,
    "feature/vehicle/VehicleCapture.kt" to 3,
    "feature/vehicle/VehicleReaders.kt" to 2,
    "feature/vehicle/VehicleTelemetryHub.kt" to 7,
    "feature/warning/WarningJournal.kt" to 4,
    "feature/warning/WarningRecorder.kt" to 6,
    "feature/warning/WarningText.kt" to 9,
    "platform/accessibility/AccessibilityServiceSettings.kt" to 4,
)

private val TABLE = allow(
    Reason.TABLE,
    "feature/locale/SystemLanguage.kt" to 4,
)

private val ALLOWED: Map<String, Allowed> = EXTERNAL + PENDING + REPORT + LOG + TABLE

internal fun violations(
    found: Map<String, List<CyrillicLiteral>>,
    allowed: Map<String, Allowed>,
    parked: String,
): List<String> {
    val problems = mutableListOf<String>()
    found.toSortedMap().filterKeys { !it.startsWith(parked) || it in allowed }.forEach { (file, literals) ->
        val entry = allowed[file]
        val shown = literals.joinToString("; ", limit = 8) { "${it.line}: ${it.text}" }
        when {
            entry == null -> problems +=
                "$file: new file with ${literals.size} Cyrillic literals. A driver's word goes to " +
                "strings.xml through AppText (docs/system-language.md). $shown"
            literals.size > entry.max -> problems +=
                "$file: ${literals.size} literals, the list allows ${entry.max} (${entry.reason}). " +
                "A driver's word goes to strings.xml through AppText. $shown"
            literals.size < entry.max -> problems +=
                "$file: ${literals.size} literals, the list still says ${entry.max}: lower the entry."
        }
    }
    (allowed.keys - found.keys).sorted().forEach { file ->
        problems += "$file: on the list with no literals: delete the entry."
    }
    return problems
}

private val FORBIDDEN_CALLS = listOf(
    Regex("""\b(?:stringResource|pluralStringResource)\s*\("""),
    Regex(
        """\b(?:getString|getText|setText|getQuantityString|getQuantityText)\s*\(\s*""" +
            """(?:\w+\s*\.\s*)*R\s*\.\s*(?:string|plurals)\b""",
    ),
)

internal fun forbiddenCalls(code: String): List<String> =
    FORBIDDEN_CALLS.flatMap { pattern -> pattern.findAll(code).map { it.value }.toList() }

private val APP_WORDS = Regex("""\bAppText\b|\bR\s*\.\s*(?:string|plurals)\b""")

/** Where this code reaches the app's own words, which an [EXTERNAL] file never does. */
internal fun externalTextUses(code: String): List<String> =
    APP_WORDS.findAll(code).map { it.value }.toList()

internal class CyrillicLiteral(val line: Int, val text: String)

internal class Lexed(val literals: List<CyrillicLiteral>, val code: String)

/**
 * Kotlin or Java, as far as telling comments, literals and code apart takes: line and block
 * comments, raw strings, `$` and `${}` templates with whatever a template holds, `$$"…"` prefixes,
 * char literals and backticked names. Java's block comments do not nest and its strings have no templates.
 *
 * Collects the literals holding Cyrillic and the code with comments and literals taken out. A
 * literal's own text leaves out its templates, so `"${f("да")}"` is one literal, the inner one.
 */
internal class SourceLexer(private val text: String, private val kotlin: Boolean) {
    private val literals = mutableListOf<CyrillicLiteral>()
    private val code = StringBuilder()
    private var i = 0
    private var line = 1

    fun lex(): Lexed {
        code(inTemplate = false)
        return Lexed(literals, code.toString())
    }

    private fun code(inTemplate: Boolean) {
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                at("//") -> lineComment()
                at("/*") -> blockComment()
                kotlin && c == '$' && text.getOrNull(i + dollarsAt(i)) == '"' -> {
                    val dollars = dollarsAt(i)
                    i += dollars
                    if (at("\"\"\"")) multilineString(dollars) else string(dollars)
                }
                at("\"\"\"") -> multilineString(1)
                c == '"' -> string(1)
                c == '\'' -> charLiteral()
                c == '`' && kotlin -> backticked()
                inTemplate && c == '}' && depth == 0 -> {
                    i += 1
                    return
                }
                else -> {
                    if (c == '{') depth += 1
                    if (c == '}') depth -= 1
                    code.append(next())
                }
            }
        }
        if (inTemplate) error("a template is not closed")
    }

    private fun lineComment() {
        while (i < text.length && text[i] != '\n') i += 1
    }

    private fun blockComment() {
        val start = line
        var depth = 0
        do {
            when {
                i >= text.length -> error("the comment from line $start is not closed")
                at("/*") && (kotlin || depth == 0) -> {
                    depth += 1
                    i += 2
                }
                at("*/") -> {
                    depth -= 1
                    i += 2
                }
                else -> next()
            }
        } while (depth > 0)
        code.append(' ')
    }

    /** [dollars] is how many a template takes: two after a `$$` prefix, and normally one. */
    private fun string(dollars: Int) {
        val start = line
        val own = StringBuilder()
        i += 1
        while (true) {
            if (i >= text.length || text[i] == '\n') error("the string on line $start is not closed")
            when {
                text[i] == '"' -> {
                    i += 1
                    break
                }
                text[i] == '\\' -> own.append(escape())
                text[i] == '$' && kotlin -> template(own, dollars)
                else -> own.append(next())
            }
        }
        literal(start, own)
    }

    private fun multilineString(dollars: Int) {
        val start = line
        val own = StringBuilder()
        i += 3
        while (true) {
            if (i >= text.length) error("the raw string from line $start is not closed")
            when {
                at("\"\"\"") -> {
                    // Kotlin: in a run of quotes the last three close, and the rest are text.
                    var run = 3
                    while (kotlin && i + run < text.length && text[i + run] == '"') run += 1
                    repeat(run - 3) { own.append('"') }
                    i += run
                    break
                }
                text[i] == '\\' && !kotlin -> own.append(escape())
                text[i] == '$' && kotlin -> template(own, dollars)
                else -> own.append(next())
            }
        }
        literal(start, own)
    }

    /** In a run of dollars the last [dollars] open the template, and the ones before are text. */
    private fun template(own: StringBuilder, dollars: Int) {
        val run = dollarsAt(i)
        val after = text.getOrNull(i + run)
        val opens = after == '{' || after == '`' || (after != null && (after.isLetter() || after == '_'))
        if (run < dollars || !opens) {
            repeat(run) { own.append(next()) }
            return
        }
        repeat(run - dollars) { own.append(next()) }
        i += dollars
        when (after) {
            '{' -> {
                i += 1
                code(inTemplate = true)
            }
            '`' -> backticked()
            else -> while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i += 1
        }
    }

    private fun dollarsAt(from: Int): Int {
        var run = 0
        while (text.getOrNull(from + run) == '$') run += 1
        return run
    }

    private fun charLiteral() {
        val start = line
        i += 1
        val own = StringBuilder()
        if (text.getOrNull(i) == '\\') own.append(escape()) else if (i < text.length) own.append(next())
        if (text.getOrNull(i) != '\'') error("the char on line $start is not closed")
        i += 1
        literal(start, own)
    }

    private fun backticked() {
        val start = line
        i += 1
        while (text.getOrNull(i) != '`') {
            if (i >= text.length || text[i] == '\n') error("the name on line $start is not closed")
            i += 1
        }
        i += 1
        code.append('_')
    }

    /** At the backslash: what it stands for, so that `\u0416` counts as the letter it is. */
    private fun escape(): Char {
        i += 1
        if (text.getOrNull(i) == 'u') {
            val value = text.substring(i + 1, minOf(i + 5, text.length)).toIntOrNull(16)
                ?: error("a bad \\u escape on line $line")
            i += 5
            return value.toChar()
        }
        if (i >= text.length) error("a backslash ends the file")
        return next()
    }

    private fun literal(start: Int, own: StringBuilder) {
        code.append("\"\"")
        if (own.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }) {
            literals += CyrillicLiteral(start, own.toString())
        }
    }

    private fun at(token: String) = text.startsWith(token, i)

    private fun next(): Char {
        val c = text[i]
        if (c == '\n') line += 1
        i += 1
        return c
    }
}
