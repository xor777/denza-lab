package dev.denza.apps.feature.hud

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared accessibility service sees views their apps mark unimportant for accessibility.
 *
 * Yandex Navigator's speed sign is one: its `text_speedlimit` sits inside a clickable
 * `speedlimitview_guidance`, which takes it over, so without `flagIncludeNotImportantViews` the
 * lookup by id finds nothing. On the car on 2026-10-11 Yandex showed a 20 sign while every road
 * packet went out with no limit, and the car's own sign stayed empty.
 */
class YandexSpeedSignAccessContractTest {
    @Test
    fun theSharedServiceIncludesViewsMarkedUnimportant() {
        val config = File("src/main/res/xml/simulcast_a11y_service.xml").readText()
        val flags = Regex("""android:accessibilityFlags="([^"]*)"""").find(config)?.groupValues?.get(1).orEmpty()

        assertTrue(flags, "flagIncludeNotImportantViews" in flags.split('|'))
    }
}
