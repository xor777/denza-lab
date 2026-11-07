package dev.denza.apps.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDecimalTest {
    @Test
    fun theMarkIsTheLanguagesAndNotTheCars() {
        assertEquals(',', AppDecimal.separator(Locale.forLanguageTag("ru-RU")))
        assertEquals('.', AppDecimal.separator(Locale.forLanguageTag("en-US")))
        val car = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("the car's language does not reach it", "1,2", AppDecimal.tenths(12))
        } finally {
            Locale.setDefault(car)
        }
    }

    @Test
    fun aFigurePrintedWithAPointIsWrittenInTheLanguage() {
        assertEquals("12,8", AppDecimal.written("12.8"))
        assertEquals("-0,4", AppDecimal.written("-0.4"))
        assertEquals("no mark, nothing to do", "128", AppDecimal.written("128"))
    }

    @Test
    fun aCountOfTenths() {
        assertEquals("0,0", AppDecimal.tenths(0))
        assertEquals("1,2", AppDecimal.tenths(12))
        assertEquals("-0,4", AppDecimal.tenths(-4))
        assertEquals("128,0", AppDecimal.tenths(1280))
    }
}
