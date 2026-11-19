package dev.denza.apps.feature.alice

import org.junit.Assert.*
import org.junit.Test

class NavigatorVoiceControlsTest {
    private data class Control(
        val id: String?,
        val visible: Boolean = true,
        val enabled: Boolean = true,
        val clickable: Boolean = true,
        val className: String = "android.widget.Button",
        val parent: Control? = null,
    )

    private fun select(controls: List<Control>) = NavigatorVoiceControls.findUnique(
        find = { id -> controls.filter { it.id == id } },
        usable = { it.visible && it.enabled && it.clickable },
        mapButtonForIcon = { icon ->
            icon.parent?.takeIf {
                icon.visible && icon.enabled && NavigatorVoiceControls.isMapVoiceButton(it.id, it.className)
            }
        },
    )

    private fun mapVoiceIcon(button: Control) = Control(
        id = "ru.yandex.yandexnavi:id/control_voice_search_image",
        clickable = false,
        className = "android.widget.ImageView",
        parent = button,
    )

    @Test fun `active route uses guidance control instead of free map control`() {
        // Live 2026-10-11, guidance-fix/click.txt: window 336, ACTION_CLICK true, MIC active.
        val guidance = Control("ru.yandex.yandexnavi:id/guidance_open_voice_search")
        assertSame(guidance, select(listOf(Control("ru.yandex.yandexnavi:id/resetroutebutton2"), guidance)))
    }

    @Test fun `free map microphone still works`() {
        val map = Control("ru.yandex.yandexnavi:id/navi_service_open_voice_search")
        assertSame(map, select(listOf(map)))
    }

    @Test fun `hidden old layout does not hide the visible guidance target`() {
        val guidance = Control("ru.yandex.yandexnavi:id/guidance_open_voice_search")
        assertSame(guidance, select(listOf(
            Control("ru.yandex.yandexnavi:id/navi_service_open_voice_search", visible = false), guidance,
        )))
    }

    @Test fun `ambiguous disabled and unrelated controls cannot trigger a click`() {
        val map = Control("ru.yandex.yandexnavi:id/navi_service_open_voice_search")
        val guidance = Control("ru.yandex.yandexnavi:id/guidance_open_voice_search")
        assertNull(select(listOf(map, guidance)))
        assertNull(select(listOf(guidance, guidance)))
        assertNull(select(listOf(guidance.copy(enabled = false))))
        assertNull(select(listOf(Control("ru.yandex.yandexnavi:id/alice_oknyx"))))
    }

    @Test fun `free map on Navigator 31 uses the button around the voice search icon`() {
        // YandexHUD 34 on the car: the image has a resource ID and its clickable parent does not.
        val button = Control(id = null)
        assertSame(button, select(listOf(mapVoiceIcon(button), button)))
    }

    @Test fun `exact guidance or legacy map targets take precedence over the icon fallback`() {
        val icon = mapVoiceIcon(Control(id = null))
        val guidance = Control("ru.yandex.yandexnavi:id/guidance_open_voice_search")
        val legacy = Control("ru.yandex.yandexnavi:id/navi_service_open_voice_search")
        assertSame(guidance, select(listOf(guidance, icon)))
        assertSame(legacy, select(listOf(legacy, icon)))
        assertNull(select(listOf(guidance, legacy, icon)))
        assertNull(select(listOf(guidance.copy(enabled = false), icon)))
    }

    @Test fun `fallback requires a unique icon under a usable unlabelled button`() {
        val button = Control(id = null)
        val icon = mapVoiceIcon(button)
        assertNull(select(listOf(icon, mapVoiceIcon(button.copy()))))
        assertNull(select(listOf(icon.copy(visible = false))))
        assertNull(select(listOf(icon.copy(enabled = false))))
        assertNull(select(listOf(icon.copy(parent = null))))
        assertNull(select(listOf(mapVoiceIcon(button.copy(clickable = false)))))
        assertNull(select(listOf(mapVoiceIcon(button.copy(enabled = false)))))
        assertNull(select(listOf(mapVoiceIcon(button.copy(visible = false)))))
        assertNull(select(listOf(mapVoiceIcon(button.copy(className = "android.widget.FrameLayout")))))
        assertNull(select(listOf(mapVoiceIcon(button.copy(id = "ru.yandex.yandexnavi:id/alice_oknyx")))))
    }
}
