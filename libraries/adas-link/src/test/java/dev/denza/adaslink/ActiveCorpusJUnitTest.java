package dev.denza.adaslink;

import java.io.File;
import org.junit.Test;

/** Explicit retained input corpus checks: ./gradlew -PadasCorpus :adas-link:test. */
public class ActiveCorpusJUnitTest {
    private static File root() { return new File(System.getProperty("denza.root", "../..")); }
    @Test public void originalDecodedVectorInputs() throws Exception { VectorRoadTileTest.corpus(root().toPath()); }
    @Test public void originalOfflinePackageInputs() throws Exception { OfflineVectorPackageTest.corpus(root().toPath()); }
    @Test public void originalGraphInputs() throws Exception { GraphTileTest.captured(root()); GraphTileCacheTest.captured(root()); }
}
