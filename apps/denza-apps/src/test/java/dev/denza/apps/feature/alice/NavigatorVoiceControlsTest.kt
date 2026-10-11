package dev.denza.apps.feature.alice

import org.junit.Assert.*
import org.junit.Test

class NavigatorVoiceControlsTest {
    private data class Control(val id: String, val visible: Boolean = true, val enabled: Boolean = true)

    private fun select(controls: List<Control>) = NavigatorVoiceControls.findUnique(
        find = { id -> controls.filter { it.id == id } },
        usable = { it.visible && it.enabled },
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
}
