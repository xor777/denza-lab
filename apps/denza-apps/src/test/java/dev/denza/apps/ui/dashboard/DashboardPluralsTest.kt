package dev.denza.apps.ui.dashboard

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.feature.defaultapps.DefaultAppRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `<plurals>` the dashboard's hand-written Russian became, against those helpers, kept here as
 * they were. The range holds the teens (11 to 14, 111 to 114) and their neighbours (21 to 24).
 */
class DashboardPluralsTest {
    private val counts = 0..200

    private fun oldPlural(count: Int, one: String, few: String, many: String): String {
        val tail = count % 100
        val last = count % 10
        return when {
            tail in 11..14 -> many
            last == 1 -> one
            last in 2..4 -> few
            else -> many
        }
    }

    private fun oldFeatureCount(count: Int) = "$count ${oldPlural(count, "функция", "функции", "функций")}"

    private fun oldVerb(count: Int, singular: String, plural: String): String {
        val tail = count % 100
        return if (tail !in 11..14 && count % 10 == 1) singular else plural
    }

    private fun oldApplications(count: Int) =
        "$count ${oldPlural(count, "приложение", "приложения", "приложений")}"

    private fun oldConfiguredApps(count: Int) = when (count) {
        1 -> "1 настроено"
        else -> "$count настроены"
    }

    private fun assertSame(old: (Int) -> String, new: (Int) -> String) =
        counts.forEach { assertEquals("count $it", old(it), new(it)) }

    @Test
    fun minutesAgo() = assertSame(
        { "$it ${oldPlural(it, "минуту", "минуты", "минут")} назад" },
        { AppText.p(R.plurals.time_minutes_ago, it, it) },
    )

    @Test
    fun hoursAgo() = assertSame(
        { "$it ${oldPlural(it, "час", "часа", "часов")} назад" },
        { AppText.p(R.plurals.time_hours_ago, it, it) },
    )

    /** `ago` reaches the plurals with the count it worked out. */
    @Test
    fun agoSaysWhatTheOldOneSaid() {
        (1..59).forEach { minutes ->
            assertEquals(
                "$minutes ${oldPlural(minutes, "минуту", "минуты", "минут")} назад",
                DashboardTiles.ago(minutes * 60_000L),
            )
        }
        (1..23).forEach { hours ->
            assertEquals(
                "$hours ${oldPlural(hours, "час", "часа", "часов")} назад",
                DashboardTiles.ago(hours * 3_600_000L),
            )
        }
    }

    @Test
    fun featuresWaiting() = assertSame(
        { "${oldFeatureCount(it)} ${oldVerb(it, "ждёт", "ждут")}" },
        { AppText.p(R.plurals.tile_service_waiting, it, it) },
    )

    @Test
    fun applicationsFromTheHeadUnit() = assertSame(
        { "${oldApplications(it)} с головного устройства" },
        { AppText.p(R.plurals.pickers_fse_subtitle, it, it) },
    )

    /**
     * The one that cannot match everywhere. The old helper took only the digit 1 as singular, while
     * Russian's `one` also holds 21, 31 and 101, so a plural says «21 настроено» where it said
     * «21 настроены». The count is of roles, so every value a car can reach is the same.
     */
    @Test
    fun configuredApps() {
        val differing = counts.filter { oldConfiguredApps(it) != DashboardTiles.configuredApps(it) }
        assertEquals((21..200 step 10).filter { it % 100 != 11 }, differing)
        assertTrue(differing.min() > DefaultAppRole.entries.size)
        (0..DefaultAppRole.entries.size).forEach {
            assertEquals(oldConfiguredApps(it), DashboardTiles.configuredApps(it))
        }
    }
}
