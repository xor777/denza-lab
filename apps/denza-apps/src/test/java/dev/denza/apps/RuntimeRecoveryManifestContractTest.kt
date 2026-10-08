package dev.denza.apps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryManifestContractTest {
    @Test
    fun `simulcast receiver is the single boot and package replacement owner`() {
        val manifest = appManifest()
        val runtime = manifest.component("receiver", ".RuntimeRecoveryReceiver")
        val weather = manifest.component("receiver", ".feature.weather.WeatherAdapterReceiver")

        assertEquals(1, Regex("android.intent.action.BOOT_COMPLETED").findAll(manifest).count())
        assertEquals(1, Regex("android.intent.action.MY_PACKAGE_REPLACED").findAll(manifest).count())
        assertTrue(runtime.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(runtime.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(weather.contains("BOOT_COMPLETED"))
        assertFalse(weather.contains("MY_PACKAGE_REPLACED"))
        assertTrue(weather.contains("dev.denza.apps.action.WEATHER_REFRESH_ALARM"))
    }

    @Test
    fun `bootstrap service is private and device acc permission is absent`() {
        val manifest = appManifest()
        val service = manifest.component("service", ".RuntimeRecoveryService")

        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"dataSync\""))
        assertFalse(manifest.contains("android.permission.DEVICE_ACC"))
    }

    @Test
    fun `automatic adb paths remain passive and never enable adb or submit a key`() {
        val localAdb = File("src/main/java/dev/denza/apps/adb/DenzaLocalAdb.kt").readText()
        val repository = File(
            "src/main/java/dev/denza/apps/DenzaAppRepository.kt",
        ).readText()
        val autostart = repository.between("fun recoverAutostart(", "\n    fun refresh()")

        assertTrue(localAdb.contains("AuthorizationPolicy.PASSIVE"))
        assertTrue(autostart.contains("AdbRescueCoordinator.checkAccess"))
        assertFalse(autostart.contains("requestAuthorization"))
        assertFalse(autostart.contains("requestOnce"))
    }
}
