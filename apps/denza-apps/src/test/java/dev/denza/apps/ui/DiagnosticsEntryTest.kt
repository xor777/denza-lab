package dev.denza.apps.ui

import dev.denza.apps.feature.diagnostics.DiagnosticsDoor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every way into «Диагностика» goes through the service panel's page: its row sets the page, and a
 * panel saved on that page comes back on it with a recreated activity or process. The page asks one
 * door; before the agreement it is the window, and no other page opens either.
 */
class DiagnosticsEntryTest {

    /** The row in «Сервис» opens [ServicePage.DIAGNOSTICS]. */
    @Test
    fun theRowOpensTheWindowUntilTheDriverAgrees() {
        assertEquals(DiagnosticsDoor.AGREEMENT, ServicePage.DIAGNOSTICS.door(agreed = false))
        assertEquals(DiagnosticsDoor.SECTION, ServicePage.DIAGNOSTICS.door(agreed = true))
    }

    /** The saved page is the enum itself, read back by name: the same page and the same door. */
    @Test
    fun aRestoredPanelAsksTheSameDoor() {
        val restored = ServicePage.valueOf(ServicePage.DIAGNOSTICS.name)

        assertEquals(DiagnosticsDoor.AGREEMENT, restored.door(agreed = false))
        assertEquals(DiagnosticsDoor.SECTION, restored.door(agreed = true))
    }

    @Test
    fun noOtherPageOpensTheSectionOrTheWindow() {
        ServicePage.entries.filter { it != ServicePage.DIAGNOSTICS }.forEach { page ->
            assertNull(page.name, page.door(agreed = false))
            assertNull(page.name, page.door(agreed = true))
        }
    }

    /** Leaving the window is the panel the row stood on. */
    @Test
    fun leavingTheWindowIsThePanel() {
        assertEquals(ServicePage.MAIN, ServicePage.DIAGNOSTICS.parent)
    }
}
