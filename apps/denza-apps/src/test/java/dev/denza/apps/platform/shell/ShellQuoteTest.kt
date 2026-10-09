package dev.denza.apps.platform.shell

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class ShellQuoteTest {

    /** The proof that matters: a real shell gives back every string as the one word it was. */
    @Test
    fun aRealShellReadsEveryQuotedStringBackAsTheSameSingleWord() {
        assertEquals(SAMPLES, throughShell(SAMPLES.map(::shellQuote)))
    }

    /**
     * The `'"'"'` spelling two of the replaced copies used means the same word to the shell, and
     * for anything without a single quote - every package, activity, path and number those call
     * sites ever quoted - it is the same bytes too.
     */
    @Test
    fun theReplacedDoubleQuoteSpellingMeansTheSameWord() {
        assertEquals(SAMPLES, throughShell(SAMPLES.map(::doubleQuoteSpelling)))
        SAMPLES.filterNot { '\'' in it }.forEach { sample ->
            assertEquals(doubleQuoteSpelling(sample), shellQuote(sample))
        }
    }

    @Test
    fun theReplacedBackslashSpellingIsTheSameBytes() {
        SAMPLES.forEach { sample -> assertEquals(backslashSpelling(sample), shellQuote(sample)) }
    }

    @Test
    fun aPlainWordIsOnlyWrappedInQuotes() {
        assertEquals("'dev.denza.apps'", shellQuote("dev.denza.apps"))
        assertEquals("''", shellQuote(""))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    /** What `/bin/sh` makes of the [words], one argument each, read back NUL-separated. */
    private fun throughShell(words: List<String>): List<String> {
        assumeTrue("this host has no /bin/sh", File("/bin/sh").canExecute())
        val process = ProcessBuilder("/bin/sh", "-c", "printf '%s\\0' ${words.joinToString(" ")}")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        check(process.waitFor(10, TimeUnit.SECONDS)) { "the shell did not finish" }
        assertEquals(output, 0, process.exitValue())
        return output.split('\u0000').dropLast(1)
    }

    private fun doubleQuoteSpelling(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun backslashSpelling(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        /** Characters a shell treats specially, plus text it has to carry through untouched. */
        val PIECES = listOf(
            "a", "Z", "0", " ", "  ", "\t", "\n", "'", "''", "\"", "\\", "$", "\$HOME", "$(id)",
            "`id`", "!", "*", "?", "[", "]", "{", "}", "(", ")", "<", ">", "|", "&", ";", "#",
            "~", "=", "%", "^", ",", ".", ":", "/", "-", "--", "_", "+", "@", "ё", "漢", "😀",
        )

        val SAMPLES: List<String> = run {
            val random = Random(20_261_009)
            val fixed = listOf(
                "", "'", "''", "a'b", "'\\''", "'\"'\"'", "$(echo pwned)", "`id`", "a\nb",
                " leading", "trailing ", "-n", "--nice-name=x", "*", "dev.denza.apps",
                "/data/app/~~Zq3x==/dev.denza.apps-AbC==/base.apk",
            )
            fixed + List(400) {
                List(random.nextInt(0, 12)) { PIECES[random.nextInt(PIECES.size)] }.joinToString("")
            }
        }
    }
}
