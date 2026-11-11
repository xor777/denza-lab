package dev.denza.apps.ui.dashboard

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.feature.hud.HudNavigatorInstall
import dev.denza.apps.feature.hud.HudRadarInstall
import dev.denza.apps.ui.components.DenzaTileTone

/**
 * The HUD panel as words and a picture (docs/hud-output-architecture.md, 12): what the glass shows
 * during a route, and one row for each part of it, named the way the picture names it and saying
 * where it comes from. What is installed is said in those lines and nowhere else - an earlier draft
 * with rows called «Навигатор» and «Радар» described the plumbing, and a driver could not tell from
 * it what was on the glass.
 *
 * Plain Kotlin: [hudSheet] draws it, and `HudPanelBoardContractTest` holds every `sheet-hud*`
 * board of `fixtures.js` to it.
 */
internal data class HudPanel(
    val glass: HudGlass,
    val rows: List<HudPanelRow>,
) {
    companion object {
        val SUBTITLE: String get() = AppText.s(R.string.hud_panel_subtitle)

        fun of(
            navigator: HudNavigatorInstall,
            radar: HudRadarInstall,
            hints: Boolean,
            map: Boolean,
            sign: Boolean,
            cameras: Boolean,
            busy: Boolean,
        ): HudPanel {
            val alone = navigator == HudNavigatorInstall.YANDEX_HUD_ALONE
            val none = navigator == HudNavigatorInstall.NONE
            val from = when (navigator) {
                HudNavigatorInstall.YANDEX -> AppText.s(R.string.hud_panel_from_yandex)
                else -> AppText.s(R.string.hud_panel_from_yandexhud)
            }
            val signFrom = when (navigator) {
                HudNavigatorInstall.YANDEX -> AppText.s(R.string.hud_panel_sign_from_yandex)
                else -> AppText.s(R.string.hud_panel_sign_from_yandexhud)
            }
            val ours = !none && !alone
            val turns = when {
                none -> HudPanelRow(HudPanelRow.Id.TURNS, TURNS_TITLE, AppText.s(R.string.hud_panel_turns_soon), hints, false)
                alone -> HudPanelRow(HudPanelRow.Id.TURNS, TURNS_TITLE, SELF, true, false)
                else -> HudPanelRow(HudPanelRow.Id.TURNS, TURNS_TITLE, from, hints, !busy)
            }
            val mapRow = when {
                none -> HudPanelRow(HudPanelRow.Id.MAP, AppText.s(R.string.hud_panel_map), AppText.s(R.string.hud_panel_map_soon), map, false)
                alone -> HudPanelRow(HudPanelRow.Id.MAP, AppText.s(R.string.hud_panel_map), SELF, true, false)
                else -> HudPanelRow(HudPanelRow.Id.MAP, AppText.s(R.string.hud_panel_map), from, map, hints && !busy)
            }
            // A YandexHUD that draws the HUD itself leaves this app the sign alone, so there it is
            // the one switch, and turning it on turns the feature on with it.
            val signRow = when {
                none -> HudPanelRow(HudPanelRow.Id.SIGN, SIGN_TITLE, AppText.s(R.string.hud_panel_sign_soon), sign, false)
                alone -> HudPanelRow(HudPanelRow.Id.SIGN, SIGN_TITLE, signFrom, hints && sign, !busy)
                else -> HudPanelRow(
                    HudPanelRow.Id.SIGN, SIGN_TITLE,
                    signFrom, sign, hints && !busy,
                )
            }
            val camerasRow = when {
                radar == HudRadarInstall.NONE -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE,
                    AppText.s(R.string.hud_panel_cameras_install), false, false,
                )
                radar == HudRadarInstall.OFFICIAL -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE,
                    AppText.s(R.string.hud_panel_cameras_official),
                    false, false, DenzaTileTone.ATTENTION,
                )
                alone -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE,
                    AppText.s(R.string.hud_panel_cameras_old_build),
                    false, false, DenzaTileTone.ATTENTION,
                )
                none -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE, AppText.s(R.string.hud_panel_cameras_during_route), cameras, false,
                )
                else -> HudPanelRow(HudPanelRow.Id.CAMERAS, CAMERAS_TITLE, AppText.s(R.string.hud_panel_cameras_from), cameras, hints && !busy)
            }
            return HudPanel(
                glass = HudGlass(
                    turn = alone || (ours && hints),
                    sign = alone || (ours && hints && sign),
                    map = alone || (ours && hints && map),
                    cameras = ours && hints && radar == HudRadarInstall.ADAPTED && cameras,
                ),
                rows = listOf(turns, mapRow, signRow, camerasRow),
            )
        }

        private val SELF: String get() = AppText.s(R.string.hud_panel_self)
        private val TURNS_TITLE: String get() = AppText.s(R.string.hud_panel_turns_title)
        private val SIGN_TITLE: String get() = AppText.s(R.string.hud_panel_sign_title)
        // HUD Speed's own words: its base is of «потенциальных опасностей», cameras among them.
        private val CAMERAS_TITLE: String get() = AppText.s(R.string.hud_panel_cameras_title)
    }
}

/** One switch of the panel. */
internal data class HudPanelRow(
    val id: Id,
    val title: String,
    val summary: String,
    val checked: Boolean,
    val enabled: Boolean,
    val tone: DenzaTileTone? = null,
) {
    enum class Id { TURNS, MAP, SIGN, CAMERAS }
}

/**
 * What the picture of the glass lights: each part on or an empty outline. The car's own speed is
 * always there. [cameras] is HUD Speed's warning in the lane window under the turn - its one place
 * since the owner took it off the map on 2026-10-18 - with its GPS bars on the map; off, neither.
 */
internal data class HudGlass(
    val turn: Boolean,
    val sign: Boolean,
    val map: Boolean,
    val cameras: Boolean,
)
