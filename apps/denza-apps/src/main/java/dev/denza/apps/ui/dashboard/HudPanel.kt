package dev.denza.apps.ui.dashboard

import dev.denza.apps.feature.hud.HudCameraPlace
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
    /** «Где показывать камеры»: the chosen cell, or null when there are no cameras to place. */
    val place: HudCameraPlace?,
    val placeEnabled: Boolean,
    /** A paragraph under the settings, or null. */
    val note: String?,
) {
    companion object {
        const val SUBTITLE = "Так выглядит проекция во время маршрута"
        val PLACES = listOf("Внизу карты", "Под поворотом")
        const val PLACE_LABEL = "Где показывать камеры"
        const val LANES_NOTE = "Когда навигатор показывает полосы, камеры на это время уходят вниз карты."

        fun of(
            navigator: HudNavigatorInstall,
            radar: HudRadarInstall,
            hints: Boolean,
            map: Boolean,
            sign: Boolean,
            cameras: Boolean,
            place: HudCameraPlace,
            busy: Boolean,
        ): HudPanel {
            val alone = navigator == HudNavigatorInstall.YANDEX_HUD_ALONE
            val none = navigator == HudNavigatorInstall.NONE
            val from = when (navigator) {
                HudNavigatorInstall.YANDEX -> "Из Яндекс Навигатора"
                else -> "Из YandexHUD"
            }
            val ours = !none && !alone
            val turns = when {
                none -> HudPanelRow(HudPanelRow.Id.TURNS, "Повороты", "Появятся с Яндекс Навигатором или YandexHUD", hints, false)
                alone -> HudPanelRow(HudPanelRow.Id.TURNS, "Повороты", SELF, true, false)
                else -> HudPanelRow(HudPanelRow.Id.TURNS, "Повороты", from, hints, !busy)
            }
            val mapRow = when {
                none -> HudPanelRow(HudPanelRow.Id.MAP, "Карта", "Появится с Яндекс Навигатором или YandexHUD", map, false)
                alone -> HudPanelRow(HudPanelRow.Id.MAP, "Карта", SELF, true, false)
                else -> HudPanelRow(HudPanelRow.Id.MAP, "Карта", from, map, hints && !busy)
            }
            // A YandexHUD that draws the HUD itself leaves this app the sign alone, so there it is
            // the one switch, and turning it on turns the feature on with it.
            val signRow = when {
                none -> HudPanelRow(HudPanelRow.Id.SIGN, SIGN_TITLE, "Появится с навигатором", sign, false)
                alone -> HudPanelRow(HudPanelRow.Id.SIGN, SIGN_TITLE, "На знаке машины, из YandexHUD", hints && sign, !busy)
                else -> HudPanelRow(
                    HudPanelRow.Id.SIGN, SIGN_TITLE,
                    "На знаке машины, " + from.replaceFirstChar { it.lowercase() }, sign, hints && !busy,
                )
            }
            val camerasRow = when {
                radar == HudRadarInstall.NONE -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE,
                    "Появятся, если поставить HUD Speed с адаптером", false, false,
                )
                radar == HudRadarInstall.OFFICIAL -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE,
                    "Этот HUD Speed показывает их только у себя на экране. Нужна сборка с адаптером",
                    false, false, DenzaTileTone.ATTENTION,
                )
                alone -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE,
                    "Эта сборка YandexHUD не оставляет им места. В новой сборке они есть",
                    false, false, DenzaTileTone.ATTENTION,
                )
                none -> HudPanelRow(
                    HudPanelRow.Id.CAMERAS, CAMERAS_TITLE, "Из HUD Speed, во время маршрута в навигаторе", cameras, false,
                )
                else -> HudPanelRow(HudPanelRow.Id.CAMERAS, CAMERAS_TITLE, "Из HUD Speed", cameras, hints && !busy)
            }
            val placed = radar == HudRadarInstall.ADAPTED && !alone
            val shown = ours && hints && radar == HudRadarInstall.ADAPTED && cameras
            return HudPanel(
                glass = HudGlass(
                    turn = alone || (ours && hints),
                    sign = alone || (ours && hints && sign),
                    map = alone || (ours && hints && map),
                    cameras = if (shown) place else null,
                ),
                rows = listOf(turns, mapRow, signRow, camerasRow),
                place = if (placed) place else null,
                placeEnabled = placed && camerasRow.enabled && cameras,
                note = if (placed && place == HudCameraPlace.LANES) LANES_NOTE else null,
            )
        }

        private const val SELF = "YandexHUD выводит сам"
        private const val SIGN_TITLE = "Ограничение скорости"
        private const val CAMERAS_TITLE = "Камеры"
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
 * always there. [cameras] is where HUD Speed's warning stands, or null for none - and then its GPS
 * bars are not drawn either.
 */
internal data class HudGlass(
    val turn: Boolean,
    val sign: Boolean,
    val map: Boolean,
    val cameras: HudCameraPlace?,
)
