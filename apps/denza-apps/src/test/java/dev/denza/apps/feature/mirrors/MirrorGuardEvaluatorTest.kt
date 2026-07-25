package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorGuardEvaluatorTest {
    @Test
    fun stateEventTriggersOnStockCameraWhileSessionIsLive() {
        assertTrue(
            MirrorGuardEvaluator.shouldTriggerOnState(
                "com.byd.avc",
                CameraRuntimePhase.READY,
                guardEnabled = true,
            ),
        )
        assertTrue(
            MirrorGuardEvaluator.shouldTriggerOnState(
                "com.byd.avc",
                CameraRuntimePhase.STARTING,
                guardEnabled = true,
            ),
        )
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnState(
                "com.byd.dishare",
                CameraRuntimePhase.READY,
                guardEnabled = true,
            ),
        )
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnState(
                null,
                CameraRuntimePhase.READY,
                guardEnabled = true,
            ),
        )
    }

    @Test
    fun newWindowTriggersOnStockCameraTitleOnly() {
        assertTrue(
            MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                listOf("com.byd.avc/com.byd.avc.PIP2MeterActivity"),
                CameraRuntimePhase.READY,
                guardEnabled = true,
            ),
        )
        assertTrue(
            MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                listOf(null, "com.byd.avc"),
                CameraRuntimePhase.STARTING,
                guardEnabled = true,
            ),
        )
        // Our own overlay window appearing must not self-trigger.
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                listOf("dev.denza.apps"),
                CameraRuntimePhase.STARTING,
                guardEnabled = true,
            ),
        )
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                listOf(null),
                CameraRuntimePhase.READY,
                guardEnabled = true,
            ),
        )
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                emptyList(),
                CameraRuntimePhase.READY,
                guardEnabled = true,
            ),
        )
    }

    @Test
    fun disarmedWhenNoLiveSession() {
        for (phase in listOf(
            CameraRuntimePhase.IDLE,
            CameraRuntimePhase.STOPPING,
            CameraRuntimePhase.FAILED,
        )) {
            assertFalse(MirrorGuardEvaluator.armed(phase, guardEnabled = true))
            assertFalse(
                MirrorGuardEvaluator.shouldTriggerOnState("com.byd.avc", phase, guardEnabled = true),
            )
            assertFalse(
                MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                    listOf("com.byd.avc"),
                    phase,
                    guardEnabled = true,
                ),
            )
        }
    }

    @Test
    fun disarmedByFlag() {
        assertFalse(MirrorGuardEvaluator.armed(CameraRuntimePhase.READY, guardEnabled = false))
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnState(
                "com.byd.avc",
                CameraRuntimePhase.READY,
                guardEnabled = false,
            ),
        )
        assertFalse(
            MirrorGuardEvaluator.shouldTriggerOnNewWindows(
                listOf("com.byd.avc"),
                CameraRuntimePhase.READY,
                guardEnabled = false,
            ),
        )
    }
}
