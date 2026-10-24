package dev.denza.adaslink.oracle;

import static org.junit.Assert.assertEquals;

import dev.denza.adaslink.FieldContractChecks;
import java.io.File;
import java.util.ArrayList;
import org.junit.Test;

/** research/adas-bridge/field-contract-20261024.json against the encoders ({@link FieldContractChecks}). */
public class FieldContractTest {
    private static FieldContractChecks checks() throws Exception {
        return new FieldContractChecks(new File(System.getProperty("denza.root", "../..")));
    }

    @Test public void everyEmittableFieldHasARow() throws Exception {
        assertEquals("encoder fields without a contract row", new ArrayList<String>(), checks().fieldsWithoutRow());
    }

    @Test public void everyRowNamesAnExistingField() throws Exception {
        assertEquals("contract rows naming fields no encoder writes", new ArrayList<String>(), checks().rowsNamingUnknownFields());
    }

    @Test public void everyCodeLocationExists() throws Exception {
        assertEquals("contract code locations", new ArrayList<String>(), checks().brokenCodeLocations());
    }

    @Test public void decisionsCarryEvidence() throws Exception {
        assertEquals("contract decisions", new ArrayList<String>(), checks().decisionsWithoutEvidence());
    }
}
