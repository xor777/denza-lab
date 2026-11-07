package dev.denza.apps.core

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A class that reads a word while it loads would read it before `Application.attachBaseContext` if
 * anything touched the class that early, and on a car that throws. So the drawn surfaces load here
 * in a class loader of their own, where nothing has run yet, and must not reach the source.
 */
class NoWordAtClassLoadTest {

    /** Every class of the app afresh; the platform and the libraries as they are. */
    private class Fresh(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            findLoadedClass(name)?.let { return it }
            if (!name.startsWith(APP)) return super.loadClass(name, resolve)
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")?.readBytes()
                ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private val fresh = Fresh(javaClass.classLoader!!)

    private fun lookups(): Int {
        val field = Class.forName("$APP.core.StringsXmlSource", true, fresh).getDeclaredField("lookups")
        field.isAccessible = true
        return (field.get(null) as AtomicInteger).get()
    }

    @Test
    fun theDrawnSurfacesLoadWithoutReadingAWord() {
        SURFACES.forEach { name ->
            Class.forName("$APP.$name", true, fresh)
            assertEquals("$name read a word while it loaded", 0, lookups())
        }
    }

    /** The loader is what it says it is: asking this copy for a word does reach this copy's source. */
    @Test
    fun aGetterStillReadsOnce() {
        val readout = Class.forName("$APP.feature.cluster.dashboard.ContourReadout", true, fresh)
        val word = readout.getMethod("getUNIT_KW").invoke(readout.getField("INSTANCE").get(null))
        assertEquals("кВт", word)
        assertEquals(1, lookups())
    }

    private companion object {
        const val APP = "dev.denza.apps"

        val SURFACES = listOf(
            "core.AppDecimal",
            "feature.cluster.dashboard.ClusterDashboardRenderer",
            "feature.cluster.dashboard.ContourFrame",
            "feature.cluster.dashboard.ContourFrameBuilder",
            "feature.cluster.dashboard.ContourReadout",
            "feature.hud.HudRadarBadge",
            "feature.hud.HudSummaryWords",
            "feature.trip.BaseTripRenderer",
            "feature.simulcast.SimulcastScreenDiagnostics",
            "feature.trip.StripInk",
            "feature.trip.StripModel",
            "feature.trip.StripReadings",
            "feature.trip.TripPanelRenderer",
            "feature.trip.VehiclePageRenderer",
            "feature.trip.VehiclePageWords",
            "feature.vehicle.EnergyReadouts",
            "feature.vehicle.EnergyReadouts\$Direction",
            "feature.vehicle.VehicleReadFailure",
            "SupportDiagnostics",
        )
    }
}
