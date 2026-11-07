package dev.denza.apps.feature.warning

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `warning_times`, the `<plurals>` the page's hand-written «раз» became, against that helper, kept
 * here as it was. The range holds the teens (11 to 14, 111 to 114) and their neighbours (21 to 24).
 */
class WarningPluralsTest {
    private fun oldTimes(count: Int): String {
        val tens = count % 100
        val word = when {
            tens in 11..14 -> "раз"
            count % 10 == 1 -> "раз"
            count % 10 in 2..4 -> "раза"
            else -> "раз"
        }
        return "$count $word"
    }

    @Test
    fun times() = (0..200).forEach { assertEquals("count $it", oldTimes(it), WarningText.times(it)) }
}
