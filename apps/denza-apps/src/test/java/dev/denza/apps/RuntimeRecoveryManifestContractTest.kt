package dev.denza.apps

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryManifestContractTest {
    @Test
    fun `simulcast receiver is the single boot and package replacement owner`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val runtime = manifest.componentBlock("receiver", ".RuntimeRecoveryReceiver")
        val weather = manifest.componentBlock("receiver", ".feature.weather.WeatherAdapterReceiver")

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
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.componentBlock("service", ".RuntimeRecoveryService")

        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"dataSync\""))
        assertFalse(manifest.contains("android.permission.DEVICE_ACC"))
    }

    @Test
    fun `automatic adb paths remain passive and never enable adb or submit a key`() {
        val localAdb = File("src/main/java/dev/denza/apps/adb/DenzaLocalAdb.kt").readText()
        val registrar = File(
            "src/main/java/dev/denza/apps/core/AccQuickBootSurvivalRegistrar.kt",
        ).readText()
        val repository = File(
            "src/main/java/dev/denza/apps/DenzaAppRepository.kt",
        ).readText()
        val autostart = repository.substringAfter("fun recoverAutostart(")
            .substringBefore("\n    fun refresh()")

        assertTrue(localAdb.contains("AuthorizationPolicy.PASSIVE"))
        assertTrue(autostart.contains("AdbRescueCoordinator.checkAccess"))
        assertFalse(autostart.contains("requestAuthorization"))
        assertFalse(autostart.contains("requestOnce"))
        assertFalse(registrar.contains("requestAuthorization"))
        assertFalse(registrar.contains("service call adb 1"))
    }

    private fun String.componentBlock(kind: String, componentName: String): String {
        val opening = checkNotNull(
            Regex(
                "<$kind\\s+[^>]*android:name=\\\"${Regex.escape(componentName)}\\\"[^>]*>",
            ).find(this),
        ) { "$kind $componentName is missing" }
        if (opening.value.trimEnd().endsWith("/>")) return opening.value
        val closing = "</$kind>"
        val closeAt = indexOf(closing, opening.range.last + 1)
        check(closeAt >= 0) { "$kind $componentName has no closing tag" }
        return substring(opening.range.first, closeAt + closing.length)
    }
}
