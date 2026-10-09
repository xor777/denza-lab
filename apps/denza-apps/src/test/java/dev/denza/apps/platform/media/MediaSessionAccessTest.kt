package dev.denza.apps.platform.media

import dev.denza.apps.feature.hud.YandexNotificationArtworkListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionAccessTest {
    /**
     * The car holds the listener by this name in `enabled_notification_listeners`. The class may not
     * move or be renamed without the grant going with it, and the platform names it as a string so
     * that it need not reach into the HUD package for it.
     */
    @Test
    fun theListenerIsTheClassTheCarHoldsByName() {
        assertEquals(
            "dev.denza.apps.feature.hud.YandexNotificationArtworkListener",
            MediaSessionAccess.LISTENER_CLASS,
        )
        assertEquals(MediaSessionAccess.LISTENER_CLASS, YandexNotificationArtworkListener::class.java.name)
    }

    @Test
    fun alreadyEnabledAccessSkipsGrant() {
        var grantCalls = 0
        val repair = MediaSessionAccessRepair(
            isEnabled = { true },
            grant = { grantCalls += 1 },
        )

        assertEquals(MediaSessionAccessRepairResult.ALREADY_ENABLED, repair.ensure())
        assertEquals(0, grantCalls)
    }

    @Test
    fun missingAccessIsGrantedAndVerified() {
        var enabled = false
        val repair = MediaSessionAccessRepair(
            isEnabled = { enabled },
            grant = { enabled = true },
        )

        assertEquals(MediaSessionAccessRepairResult.GRANTED, repair.ensure())
        assertTrue(enabled)
    }

    @Test(expected = IllegalStateException::class)
    fun grantThatDoesNotChangeAccessFailsClosed() {
        MediaSessionAccessRepair(
            isEnabled = { false },
            grant = {},
        ).ensure()
    }

    @Test
    fun listenerSettingAcceptsFullAndShortClassNames() {
        val packageName = "dev.denza.apps"
        val className = "dev.denza.apps.feature.hud.YandexNotificationArtworkListener"

        assertTrue(
            MediaSessionAccessPolicy.isEnabled(
                "other.pkg/other.Listener:$packageName/$className",
                packageName,
                className,
            ),
        )
        assertTrue(
            MediaSessionAccessPolicy.isEnabled(
                "$packageName/.feature.hud.YandexNotificationArtworkListener",
                packageName,
                className,
            ),
        )
        assertFalse(
            MediaSessionAccessPolicy.isEnabled(
                "$packageName/.feature.hud.OtherListener",
                packageName,
                className,
            ),
        )
    }

    @Test
    fun allowCommandQuotesTheExactListenerComponent() {
        assertEquals(
            "cmd notification allow_listener " +
                "'dev.denza.apps/dev.denza.apps.feature.hud.YandexNotificationArtworkListener'",
            MediaSessionAccessPolicy.allowCommand(
                "dev.denza.apps/" +
                    "dev.denza.apps.feature.hud.YandexNotificationArtworkListener",
            ),
        )
    }
}
