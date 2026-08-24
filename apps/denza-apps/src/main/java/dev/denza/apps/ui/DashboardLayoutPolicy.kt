package dev.denza.apps.ui

/**
 * Chooses the dashboard shell from the Activity's current window width.
 *
 * The live DiLink 5.1 windows are 416 dp (narrow pane), 828 dp (wide pane), and
 * 1280 dp (fullscreen). Keeping the thresholds between those measured sizes
 * makes the decision independent of native root ids and divider side.
 */
internal object DashboardLayoutPolicy {
    const val NARROW_MAX_WIDTH_DP = 599
    const val MEDIUM_MAX_WIDTH_DP = 1_099

    fun resolve(widthDp: Int): DashboardLayoutMode = when {
        widthDp <= NARROW_MAX_WIDTH_DP -> DashboardLayoutMode.NARROW
        widthDp <= MEDIUM_MAX_WIDTH_DP -> DashboardLayoutMode.MEDIUM
        else -> DashboardLayoutMode.WIDE
    }

    /**
     * Сколько карточек группы стоит в одном ряду: вся группа на полной ширине,
     * две в средней панели 2/3, одна в узкой 1/3. Неполный последний ряд
     * растягивает свою карточку на всю ширину. Правка W8: раскладка всегда
     * вписывается в ширину контейнера - прежний холст 1280 dp в горизонтальном
     * скролле прятал ~904 px дашборда за краем панели 828 dp.
     */
    fun rowCapacity(mode: DashboardLayoutMode, cardCount: Int): Int = when (mode) {
        DashboardLayoutMode.WIDE -> cardCount
        DashboardLayoutMode.MEDIUM -> MEDIUM_ROW_CAPACITY
        DashboardLayoutMode.NARROW -> 1
    }.coerceAtLeast(1)

    private const val MEDIUM_ROW_CAPACITY = 2
}

internal enum class DashboardLayoutMode {
    WIDE,
    MEDIUM,
    NARROW,
}
