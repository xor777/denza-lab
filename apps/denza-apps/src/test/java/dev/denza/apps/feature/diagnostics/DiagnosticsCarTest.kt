package dev.denza.apps.feature.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * The reader takes the hidden method first and `getprop` after it: a firmware that refuses the method, or
     * answers it empty, still has the row by `getprop`; a route that throws is a route that said nothing.
     */
    @Test
    fun theReaderFallsBackFromTheHiddenMethodToGetprop() {
        val asked = mutableListOf<String>()
        fun route(name: String, value: () -> String?): () -> String? = { asked += name; value() }

        assertEquals("170", DiagnosticsCar.autoType(route("reflection") { throw SecurityException("hidden API") }, route("getprop") { "170\n" }))
        assertEquals(listOf("reflection", "getprop"), asked)

        asked.clear()
        assertEquals("170", DiagnosticsCar.autoType(route("reflection") { "" }, route("getprop") { "170" }))
        assertEquals(listOf("reflection", "getprop"), asked)

        asked.clear()
        assertEquals("170", DiagnosticsCar.autoType(route("reflection") { "170" }, route("getprop") { error("not asked") }))
        assertEquals(listOf("reflection"), asked)

        // neither: the car is not known, and there is no row
        assertNull(DiagnosticsCar.autoType(route("reflection") { null }, route("getprop") { null }))
        assertFalse(DiagnosticsCar.offered(DiagnosticsCar.autoType({ null }, { "   \n" })))
    }

    /** The section's own guard (the sessions' shell check) speaks of the same model. */
    @Test
    fun theRowAndTheSectionsGuardNameOneModel() {
        assertTrue(DiagnosticsCar.offered(DiagnosticsCar.AUTO_TYPE))
    }
}
