package dev.denza.adaslink.oracle;

import static org.junit.Assert.assertEquals;

import dev.denza.adaslink.AdasLinkTestAccess;
import java.io.File;
import org.junit.Test;

/** The plain-main suite (build.py runs it too) under Gradle. */
public class AdasLinkJUnitTest {
    @Test
    public void plainMainSuite() throws Exception {
        File root = new File(System.getProperty("denza.root", "../.."));
        assertEquals("adas-link failures: " + AdasLinkTestAccess.failed(), 0, AdasLinkTestAccess.run(root));
    }
}
