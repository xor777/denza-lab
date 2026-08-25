package dev.denza.apps.feature.split

/** Ширина одной ячейки сетки пикера, dp - единица и расчёта колонок, и ширины самой сетки. */
internal const val SPLIT_PICKER_GRID_CELL_WIDTH_DP = 170

/**
 * Правка W4 волны 8 (диагноз v23 Д3): сколько колонок несёт сетка пикера.
 *
 * Колонки решает сам констрейнт ширины, а не двухпозиционный порог: прежний решатель
 * `WidePaneMinimumWidth = 820.dp` знал только 2 и 4 колонки, и fullscreen-пикер шириной 1280dp
 * рисовал 4×170dp = 680dp - около половины собственной ширины. Панельные виды пиксельно
 * неизменны (416dp → 2, 828dp → 4), fullscreen 1280dp получает 7; узость никогда не опускает
 * сетку ниже двух колонок. Ширина самой сетки остаётся
 * columnCount × [SPLIT_PICKER_GRID_CELL_WIDTH_DP] по центру окна.
 */
internal fun splitPickerGridColumnCount(maxWidthDp: Float): Int =
    (maxWidthDp / SPLIT_PICKER_GRID_CELL_WIDTH_DP).toInt().coerceAtLeast(2)
