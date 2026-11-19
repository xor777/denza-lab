package dev.denza.apps.feature.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Сервис» has the «Диагностика» row on the Z9GT alone; elsewhere, or while the car is unknown, none. */
class DiagnosticsCarTest {
    @Test
    fun theCarTheCatalogWasMadeForHasTheRow() {
        assertTrue(DiagnosticsCar.offered("170"))
        // getprop's answer as the shell hands it, with its line end
        assertTrue(DiagnosticsCar.offered("170\n"))
    }

    @Test
    fun anotherModelHasNone() {
        // the N9 (docs/vehicle-data-findings.md, "Which car is this") and anything unrecognised
        assertFalse(DiagnosticsCar.offered("168"))
        assertFalse(DiagnosticsCar.offered("1700"))
        assertFalse(DiagnosticsCar.offered("17"))
    }

    @Test
    fun anUnknownCarHasNone() {
        assertFalse(DiagnosticsCar.offered(null))
        assertFalse(DiagnosticsCar.offered(""))
        assertFalse(DiagnosticsCar.offered("   "))
    }

    /** The section's own guard (the sessions' shell check) speaks of the same model. */
    @Test
    fun theRowAndTheSectionsGuardNameOneModel() {
        assertTrue(DiagnosticsCar.offered(DiagnosticsCar.AUTO_TYPE))
    }
}
