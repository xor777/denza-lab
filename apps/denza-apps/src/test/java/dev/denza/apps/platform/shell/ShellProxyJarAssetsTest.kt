package dev.denza.apps.platform.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The build packs each helper jar under a name, and [ShellProxyJar] opens the asset by a name; the
 * two are written in two languages and nothing else ties them. A jar packed under any other name
 * would leave the split and navigation on the slow APK and the turn-signal listener off, with every
 * other test green.
 */
class ShellProxyJarAssetsTest {

    @Test
    fun theBuildPacksExactlyTheJarsTheAppStages() {
        val script = File("build.gradle.kts").readText()
        val packed = JAR_LITERAL.findAll(script).map { it.groupValues[1] }.toSet()

        assertEquals(ShellProxyJar.entries.map(ShellProxyJar::asset).toSet(), packed)
    }

    /**
     * The diagnostic helper is the only one built from classes of its package besides its own:
     * the whitelist it checks again and the assembler it judges frames with. A class left out of
     * the jar compiles in the APK and fails on the car, at the first request, as a missing class.
     */
    @Test
    fun theDiagnosticHelperIsPackedWithEverythingItUses() {
        val script = File("build.gradle.kts").readText()
        val pack = Regex("""packShellProxy\(\s*"DiagnosticBus",\s*"diagnostic-bus\.jar",([^)]*)\)""")
            .find(script)?.groupValues?.get(1).orEmpty()
        val packed = Regex(""""feature/diagnostics/bus/(\w+)\.java"""").findAll(pack).map { it.groupValues[1] }.toSet()
        val helper = File("src/main/java/dev/denza/apps/feature/diagnostics/bus/DiagnosticBusMain.java").readText()
        val siblings = File("src/main/java/dev/denza/apps/feature/diagnostics/bus").listFiles().orEmpty()
            .filter { it.extension == "java" }
            .map { it.nameWithoutExtension }
            .filter { it == "DiagnosticBusMain" || Regex("""\b$it\b""").containsMatchIn(helper) }
            .toSet()

        assertEquals(setOf("DiagnosticBusMain", "DiagnosticWhitelist", "DiagnosticFrameAssembler"), siblings)
        assertEquals(siblings, packed)
    }

    private companion object {
        val JAR_LITERAL = Regex(""""([\w-]+\.jar)"""")
    }
}
