package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Правка W4 волны 8 (диагноз v23 Д3): колонки сетки пикера решает констрейнт ширины, а не
 * двухпозиционный порог 820dp. Панельные виды обязаны остаться пиксельно неизменными, а
 * fullscreen - перестать рисовать половину себя пустыми полями.
 */
class SplitPickerGridTest {

    @Test
    fun panelViewsKeepTheirExactColumnCounts() {
        assertEquals("узкая панель, как была", 2, splitPickerGridColumnCount(416f))
        assertEquals("широкая панель, как была", 4, splitPickerGridColumnCount(828f))
    }

    @Test
    fun fullscreenWidthEarnsSevenColumns() {
        assertEquals(7, splitPickerGridColumnCount(1280f))
    }

    @Test
    fun theGridNeverDropsBelowTwoColumns() {
        assertEquals(2, splitPickerGridColumnCount(120f))
        assertEquals(2, splitPickerGridColumnCount(0f))
    }

    @Test
    fun aColumnIsEarnedOnlyByAWholeCellWidth() {
        assertEquals("849dp вмещает лишь четыре ячейки по 170dp", 4, splitPickerGridColumnCount(849f))
        assertEquals(5, splitPickerGridColumnCount(850f))
    }
}
