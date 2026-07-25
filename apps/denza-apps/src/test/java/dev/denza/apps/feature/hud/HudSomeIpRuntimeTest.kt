package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class HudSomeIpRuntimeTest {
    @Test
    fun `tracks accepted delivery without route contents`() {
        HudSomeIpRuntime.resetForTest()

        HudSomeIpRuntime.onBinding()
        HudSomeIpRuntime.onStarting()
        HudSomeIpRuntime.onStartResult(0)
        HudSomeIpRuntime.onFireResult(0)

        assertEquals(
            HudSomeIpSnapshot(
                phase = HudSomeIpPhase.ACTIVE,
                lastStartResult = 0,
                lastFireResult = 0,
                recoveryAttempts = 0,
                detail = "Штатный сервис принял подсказку",
            ),
            HudSomeIpRuntime.snapshot(),
        )
    }

    @Test
    fun `records recovery reason and preserves transport results`() {
        HudSomeIpRuntime.resetForTest()

        HudSomeIpRuntime.onStartResult(-200)
        HudSomeIpRuntime.onFireResult(7)
        HudSomeIpRuntime.onRecovering("Штатный HUD отклонил подсказку: 7")

        assertEquals(
            HudSomeIpSnapshot(
                phase = HudSomeIpPhase.RECOVERING,
                lastStartResult = -200,
                lastFireResult = 7,
                recoveryAttempts = 1,
                detail = "Штатный HUD отклонил подсказку: 7",
            ),
            HudSomeIpRuntime.snapshot(),
        )
    }
}
