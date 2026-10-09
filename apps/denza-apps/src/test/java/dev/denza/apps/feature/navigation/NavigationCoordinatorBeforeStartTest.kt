package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The driver's-screen coordinator before the runtime has started it.
 *
 * A chooser saved with the screen comes back with it after the process died, and can be read and
 * pressed before the runtime starts the coordinator (`DenzaAppRepository.startAdbRuntime`). Until
 * then the coordinator holds only its default - the instruments - and has nothing to carry a
 * choice out with. «Что показывать» marked the instruments as chosen, and a tap closed the window
 * on a choice that went nowhere: the choice was reported as taken.
 *
 * The coordinator is a process-wide object that only a context starts, and no JVM test has one, so
 * it is never started here.
 */
class NavigationCoordinatorBeforeStartTest {

    @Test
    fun aChoiceBeforeTheCoordinatorStartsIsNotReportedAsTaken() {
        assertFalse(NavigationCoordinator.selectPackage("ru.yandex.yandexnavi"))
    }

    @Test
    fun beforeItStartsTheMarkIsTheDriversStoredChoice() {
        assertEquals(
            "ru.yandex.yandexnavi",
            NavigationCoordinator.selectedPackage { "ru.yandex.yandexnavi" },
        )
    }
}
