package dev.denza.apps.feature.fse

import dev.denza.apps.DenzaUiState
import dev.denza.apps.DenzaUiStateStore
import dev.denza.apps.core.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A tap in «Экран справа»'s chooser, claimed over the tile's two fields of the dashboard's state.
 *
 * The claim is the one decision the runtime makes without the car: whether this tap starts an
 * install. The install itself needs a passenger screen, and is not run here.
 */
class FseInstallRuntimeTest {
    private val store = DenzaUiStateStore()
    private val runtime = FseInstallRuntime(
        state = store.cell(
            get = { state -> FseInstallState(install = state.fseInstaller, apps = state.fseInstallApps) },
            set = { state, fse -> state.copy(fseInstaller = fse.install, fseInstallApps = fse.apps) },
        ),
        context = { null },
        executor = { error("nothing is installed without a claim") },
    )

    @Test
    fun aTapOnAnOfferedApplicationClaimsTheTileUnderItsName() {
        store.update { it.copy(fseInstallApps = listOf(app("ru.app", "Приложение"))) }

        assertEquals(FseInstallClaim.START, runtime.claim("ru.app"))

        val install = store.state.value.fseInstaller
        assertEquals(FeatureStatus.STARTING, install.status)
        assertEquals("Приложение", install.message)
        assertEquals(listOf(app("ru.app", "Приложение")), store.state.value.fseInstallApps)
    }

    /** One install at a time: the second tap finds the first under way and changes nothing. */
    @Test
    fun aSecondTapWhileTheFirstRunsIsRefusedAndWritesNothing() {
        store.update { it.copy(fseInstallApps = listOf(app("ru.app"), app("ru.other"))) }
        assertEquals(FseInstallClaim.START, runtime.claim("ru.app"))
        val claimed = store.snapshot()

        assertEquals(FseInstallClaim.BUSY, runtime.claim("ru.other"))
        assertSame(claimed, store.snapshot())
    }

    /** A list the car has moved on from answers with the chooser read again, not with a claim. */
    @Test
    fun aTapOnAnApplicationTheListNoLongerOffersIsStale() {
        store.update {
            it.copy(fseInstallApps = listOf(app("ru.split", installable = false)))
        }
        val before = store.snapshot()

        assertEquals(FseInstallClaim.STALE, runtime.claim("ru.gone"))
        assertEquals(FseInstallClaim.STALE, runtime.claim("ru.split"))
        assertSame(before, store.snapshot())
    }

    /** Before the app is initialised nothing opens and nothing starts. */
    @Test
    fun withoutAContextTheChooserStaysShutAndNoTapStarts() {
        store.update { it.copy(fseInstallApps = listOf(app("ru.app"))) }

        assertFalse(runtime.refreshApps())
        assertFalse(runtime.install("ru.app"))
        assertEquals(DenzaUiState().fseInstaller, store.state.value.fseInstaller)
    }

    private fun app(packageName: String, label: String = packageName, installable: Boolean = true) =
        FseInstallApp(packageName, label, "1", 1L, installable = installable)
}
