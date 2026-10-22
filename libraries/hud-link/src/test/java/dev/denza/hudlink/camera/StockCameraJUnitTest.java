package dev.denza.hudlink.camera;

import java.io.File;
import org.junit.Test;

/**
 * Runs the stock camera's plain-main tests under Gradle: the native oracles (CameraTest) and the
 * controller with its MapKit optics (StockCameraTest). The YandexHUD build runs the same mains.
 */
public class StockCameraJUnitTest {
    private static String research(String name) {
        return new File("../../research/yandex-hud-renderer/" + name).getPath();
    }

    @Test
    public void nativeOracles() throws Exception {
        CameraTest.main(new String[]{research("stock-camera-queue-oracle.csv"), research("stock-camera-projection-oracle.csv"),
                research("stock-camera-rule-oracle.csv"), research("stock-camera-curvature-oracle.csv")});
    }

    @Test
    public void controllerAndOptics() throws Exception {
        StockCameraTest.main(new String[]{research("stock-camera-mapkit-calibration.csv")});
    }
}
