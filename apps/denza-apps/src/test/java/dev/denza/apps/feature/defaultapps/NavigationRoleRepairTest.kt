package dev.denza.apps.feature.defaultapps

import android.content.Intent
import dev.denza.apps.feature.defaultapps.NavigationRoleRepair.Event.ADDED
import dev.denza.apps.feature.defaultapps.NavigationRoleRepair.Event.REMOVED
import dev.denza.apps.feature.defaultapps.NavigationRoleRepair.Event.REPLACED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a package broadcast asks of AutoVoice's map role.
 *
 * The broadcasts are the ones a Store update sends - `PACKAGE_REMOVED` and `PACKAGE_ADDED` with
 * `EXTRA_REPLACING`, then `PACKAGE_REPLACED` - and AutoVoice resets the role to the stock map on
 * the first of them (docs/shortcuts-automation-findings.md). `held` is what this app last
 * confirmed in the role when the broadcast came in.
 */
class NavigationRoleRepairTest {

    private val stock = DefaultAppRole.NAVIGATION.stockPackageName
    private val yandex = "ru.yandex.yandexnavi"
    private val dgis = "ru.dublgis.dgismobile"

    private fun repair() = NavigationRoleRepair(stock)

    @Test
    fun aStoreUpdateOfTheChosenNavigatorPutsItBackOnceItIsInstalled() {
        val repair = repair()
        assertNull(repair.on(REMOVED, yandex, replacing = true, held = yandex))
        // By the time it is back the app may already have read AutoVoice's reset.
        assertEquals(yandex, repair.on(ADDED, yandex, replacing = true, held = stock))
        repair.restored(yandex)
        assertNull(repair.on(REPLACED, yandex, replacing = false, held = yandex))
    }

    /** AutoVoice's own receiver may not have reset the role yet when the added event comes. */
    @Test
    fun aRestoreThatFoundNothingToUndoIsTriedOnceMoreAndThenLetGo() {
        val repair = repair()
        repair.on(REMOVED, yandex, replacing = true, held = yandex)
        assertEquals(yandex, repair.on(ADDED, yandex, replacing = true, held = yandex))
        assertEquals(yandex, repair.on(REPLACED, yandex, replacing = false, held = yandex))
        // Bounded: the next update starts from what the app has confirmed by then.
        assertNull(repair.on(REPLACED, yandex, replacing = false, held = yandex))
    }

    /**
     * «Штатные» from the Shortcuts switch keeps the navigator as the remembered pick, so the pick
     * alone would have undone the driver's choice on the next update.
     */
    @Test
    fun aRoleTheDriverHandedBackToTheCarStaysWithTheCar() {
        val repair = repair()
        assertNull(repair.on(REMOVED, yandex, replacing = true, held = stock))
        assertNull(repair.on(ADDED, yandex, replacing = true, held = stock))
        assertNull(repair.on(REPLACED, yandex, replacing = false, held = stock))
    }

    @Test
    fun anUpdateOfANavigatorTheRoleDoesNotHoldIsNotOurs() {
        val repair = repair()
        assertNull(repair.on(REMOVED, dgis, replacing = true, held = yandex))
        assertNull(repair.on(ADDED, dgis, replacing = true, held = yandex))
        assertNull(repair.on(REPLACED, dgis, replacing = false, held = yandex))
    }

    /** An uninstalled navigator is AutoVoice's reset to make, and a later install is a new app. */
    @Test
    fun anUninstallIsLeftToAutoVoice() {
        val repair = repair()
        assertNull(repair.on(REMOVED, yandex, replacing = false, held = yandex))
        assertNull(repair.on(ADDED, yandex, replacing = false, held = stock))

        // Armed by an update, then removed for good before it came back.
        repair.on(REMOVED, yandex, replacing = true, held = yandex)
        assertNull(repair.on(REMOVED, yandex, replacing = false, held = stock))
        assertNull(repair.on(ADDED, yandex, replacing = true, held = stock))
        assertNull(repair.on(REPLACED, yandex, replacing = false, held = stock))
    }

    @Test
    fun otherPackagesComingAndGoingDoNotDisarmIt() {
        val repair = repair()
        repair.on(REMOVED, yandex, replacing = true, held = yandex)
        assertNull(repair.on(REMOVED, dgis, replacing = false, held = stock))
        assertNull(repair.on(ADDED, dgis, replacing = false, held = stock))
        assertEquals(yandex, repair.on(ADDED, yandex, replacing = true, held = stock))
    }

    @Test
    fun onlyThePackageBroadcastsTheRoleCanBeLostByAreRead() {
        assertEquals(REMOVED, NavigationRoleRepair.eventOf(Intent.ACTION_PACKAGE_REMOVED))
        assertEquals(ADDED, NavigationRoleRepair.eventOf(Intent.ACTION_PACKAGE_ADDED))
        assertEquals(REPLACED, NavigationRoleRepair.eventOf(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(NavigationRoleRepair.eventOf(Intent.ACTION_PACKAGE_CHANGED))
        assertNull(NavigationRoleRepair.eventOf(null))
    }
}
