package dev.denza.apps.feature.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockClusterModeDetectorTest {
    @Test
    fun detectsVisibleStockMapOnSelectedClusterDisplay() {
        assertTrue(StockClusterModeDetector.isMapVisible(mapSnapshot, 3))
    }

    @Test
    fun rejectsStockMapOnAnotherDisplay() {
        assertFalse(StockClusterModeDetector.isMapVisible(mapSnapshot, 4))
    }

    @Test
    fun rejectsAdasSceneWithoutMeterActivity() {
        assertFalse(StockClusterModeDetector.isMapVisible(adasSnapshot, 3))
    }

    @Test
    fun rejectsHiddenStockMapTask() {
        assertFalse(
            StockClusterModeDetector.isMapVisible(
                mapSnapshot.replace("visible=true", "visible=false"),
                3,
            ),
        )
    }

    private companion object {
        val mapSnapshot = """
            RootTask id=64 bounds=[0,0][2560,720] displayId=3 userId=0
              taskId=64: com.byd.launchermap/com.byd.automap.meter.MeterActivity bounds=[0,0][2560,720] userId=0 visible=true topActivity=ComponentInfo{com.byd.launchermap/com.byd.automap.meter.MeterActivity}

            RootTask id=12 bounds=[0,0][2560,720] displayId=3 userId=0
              taskId=12: com.example.amapservice/com.byd.cluster.projectionmanager.service.BottomEmptyActivity bounds=[0,0][2560,720] userId=0 visible=true topActivity=ComponentInfo{com.example.amapservice/com.byd.cluster.projectionmanager.service.BottomEmptyActivity}
        """.trimIndent()

        val adasSnapshot = """
            RootTask id=65 bounds=[0,0][2560,720] displayId=3 userId=0
              taskId=65: com.byd.sr/com.byd.sr.cluster.ClusterActivity bounds=[0,0][2560,720] userId=0 visible=true topActivity=ComponentInfo{com.byd.sr/com.byd.sr.cluster.ClusterActivity}

            RootTask id=12 bounds=[0,0][2560,720] displayId=3 userId=0
              taskId=12: com.example.amapservice/com.byd.cluster.projectionmanager.service.BottomEmptyActivity bounds=[0,0][2560,720] userId=0 visible=true topActivity=ComponentInfo{com.example.amapservice/com.byd.cluster.projectionmanager.service.BottomEmptyActivity}
        """.trimIndent()
    }
}
