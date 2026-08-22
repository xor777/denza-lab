package dev.denza.apps.feature.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockRussianLocaleCoordinatorTest {
    @Test
    fun inspectReturnsUnknownBeforeDenzaAppsHasAppliedAChoice() {
        val repair = StockRussianLocaleRepair(
            readSavedState = { null },
            writeDirectLocale = {},
            saveState = {},
        )

        assertNull(repair.inspect())
    }

    @Test
    fun enablingRussianUsesDirectWriterThenSavesChoice() {
        val calls = mutableListOf<String>()
        var saved: Boolean? = null
        val repair = StockRussianLocaleRepair(
            readSavedState = { saved },
            writeDirectLocale = { enabled -> calls += "direct:$enabled" },
            saveState = { enabled ->
                calls += "save:$enabled"
                saved = enabled
            },
        )

        val change = repair.setEnabled(true)

        assertEquals(StockRussianLocaleChange.CHANGED, change)
        assertEquals(listOf("direct:true", "save:true"), calls)
        assertTrue(saved == true)
    }

    @Test
    fun disablingRussianUsesAnEmptyLocaleListChoice() {
        var directChoice: Boolean? = null
        var saved = true
        val repair = StockRussianLocaleRepair(
            readSavedState = { saved },
            writeDirectLocale = { enabled -> directChoice = enabled },
            saveState = { enabled -> saved = enabled },
        )

        val change = repair.setEnabled(false)

        assertEquals(StockRussianLocaleChange.CHANGED, change)
        assertFalse(directChoice ?: true)
        assertFalse(saved)
        assertEquals("", StockRussianLocalePolicy.languageTags(false))
    }

    @Test
    fun matchingSavedChoiceIsStillReappliedToRepairExternalDrift() {
        var directCalls = 0
        val repair = StockRussianLocaleRepair(
            readSavedState = { true },
            writeDirectLocale = { directCalls += 1 },
            saveState = {},
        )

        val change = repair.setEnabled(true)

        assertEquals(StockRussianLocaleChange.REAPPLIED, change)
        assertEquals(1, directCalls)
    }

    @Test
    fun failedDirectWriteDoesNotSaveMisleadingState() {
        var saveCalls = 0
        val repair = StockRussianLocaleRepair(
            readSavedState = { false },
            writeDirectLocale = { throw SecurityException("permission denied") },
            saveState = { saveCalls += 1 },
        )

        val error = runCatching { repair.setEnabled(true) }.exceptionOrNull()

        assertTrue(error is SecurityException)
        assertEquals(0, saveCalls)
    }

    @Test
    fun permissionGrantCommandIsFixedToThisOneDevelopmentPermission() {
        assertEquals(
            "pm grant dev.denza.apps android.permission.CHANGE_CONFIGURATION",
            StockRussianLocalePolicy.permissionGrantCommand("dev.denza.apps"),
        )
    }

    @Test
    fun enabledChoiceMapsOnlyToStockRussianTag() {
        assertEquals("ru-RU", StockRussianLocalePolicy.languageTags(true))
        assertEquals("com.byd.carsettings", StockRussianLocalePolicy.TARGET_PACKAGE)
    }
}
