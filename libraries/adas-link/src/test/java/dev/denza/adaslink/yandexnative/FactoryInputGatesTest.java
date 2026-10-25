package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.AdasGateSnapshotTest;
import dev.denza.adaslink.FixFreshnessTest;
import dev.denza.adaslink.LiveSessionLeaseTest;
import dev.denza.adaslink.stocknative.NativeSenderCoreChecks;
import org.junit.Test;

/** Input/lifecycle checks; recorded original APK outputs remain the sender oracle. */
public final class FactoryInputGatesTest {
    @Test public void actualReadProvenanceAndFreshness() {
        AdasGateSnapshotTest.main(new String[0]);
        FixFreshnessTest.main(new String[0]);
        LiveSessionLeaseTest.main(new String[0]);
    }
    @Test public void independentCoreInvariants() throws Exception {
        NativeSenderCoreChecks.main(new String[0]);
    }
}
