package dev.denza.adaslink;

import java.io.File;
import org.junit.Test;

/** Explicit retained input corpus checks: ./gradlew -PadasCorpus :adas-link:test. */
public class ActiveCorpusJUnitTest {
    private static File root() { return new File(System.getProperty("denza.root", "../..")); }
    @Test public void originalDecodedVectorInputs() throws Exception { VectorRoadTileTest.corpus(root().toPath()); }
    @Test public void originalOfflinePackageInputs() throws Exception { OfflineVectorPackageTest.corpus(root().toPath()); }
    @Test public void originalGraphInputs() throws Exception { GraphTileTest.captured(root()); GraphTileCacheTest.captured(root()); }
    @Test public void originalNativeCoordinateVectors() throws Exception {
        // Saved original AutoDice inner routine output, not the former bridge's output.
        java.nio.file.Path fixture = root().toPath().resolve("captures/drive-kit-20261025/postdrive-20261024-204337/reports/coordinate-parity/batch/results.json");
        byte[] bytes = java.nio.file.Files.readAllBytes(fixture);
        StringBuilder sha = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", b & 255));
        org.junit.Assert.assertEquals("59b1db2e1d1caa67cd2dd3a6e394cf0968cf561a137fa066bc5b280f880e818b", sha.toString());
        java.util.Map<?, ?> report = (java.util.Map<?, ?>) Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        java.util.List<?> rows = (java.util.List<?>) report.get("rows");
        org.junit.Assert.assertEquals(408, rows.size());
        for (Object value : rows) {
            java.util.Map<?, ?> row = (java.util.Map<?, ?>) value;
            double lon = ((Number) row.get("lon")).doubleValue(), lat = ((Number) row.get("lat")).doubleValue();
            double[] actual = Coordinates.toSdk(lon, lat, Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
            double[] identity = Coordinates.toSdk(lon, lat, Coordinates.Frame.WGS84);
            java.util.List<?> expected = (java.util.List<?>) row.get("originalInner");
            java.util.List<?> grid = (java.util.List<?>) row.get("originalInnerStockGrid");
            for (int axis = 0; axis < 2; axis++) {
                org.junit.Assert.assertEquals(row.get("id") + " native axis " + axis,
                        Double.doubleToLongBits(((Number) expected.get(axis)).doubleValue()), Double.doubleToLongBits(actual[axis]));
                org.junit.Assert.assertEquals(row.get("id") + " wire grid axis " + axis,
                        Double.doubleToLongBits(((Number) grid.get(axis)).doubleValue()), Double.doubleToLongBits(Coordinates.grid(actual[axis])));
            }
            org.junit.Assert.assertArrayEquals(new double[]{lon, lat}, identity, 0.0);
        }
    }
}
