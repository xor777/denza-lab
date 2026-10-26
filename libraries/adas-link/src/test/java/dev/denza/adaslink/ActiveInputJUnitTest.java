package dev.denza.adaslink;

import static org.junit.Assert.*;
import org.junit.Test;

/** Independent active input/parser checks. No old sender or route-output golden is an oracle. */
public class ActiveInputJUnitTest {
    @Test public void vectorParserBoundaries() {
        VectorRoadTileTest.synthetic(); VectorRoadTileTest.typedValues(); VectorRoadTileTest.bad();
        VectorTilePacketTest.main(new String[0]);
    }
    @Test public void vectorCacheLifecycle() throws Exception { VectorRoadTileCacheTest.main(new String[0]); }
    @Test public void offlinePackageParserBoundaries() throws Exception {
        OfflineVectorPackageTest.synthetic(); OfflineVectorPackageTest.malformed(); OfflineVectorPackageTest.largeScopedMapping();
    }
    @Test public void graphParserBoundaries() throws Exception { GraphTileTest.synthetic(); }
    @Test public void graphCacheLifecycle() throws Exception {
        GraphTileCacheTest.basic(); GraphTileCacheTest.budgets(); GraphTileCacheTest.lifecycleRace(false);
        GraphTileCacheTest.lifecycleRace(true); GraphTileCacheTest.concurrentDedup();
    }
    @Test public void routeAttributeSourceAndOwnership() {
        RouteVectorAttributesTest.main(new String[0]); RouteVectorTilePlanTest.main(new String[0]);
        YandexRoadClassTest.main(new String[0]);
    }
    @Test public void hardwareGateAndLeaseInputs() {
        AdasGateSnapshotTest.main(new String[0]); OfflineMapGateTest.main(new String[0]); LiveSessionLeaseTest.main(new String[0]);
    }
    @Test public void freshnessPreservesHeldFixAgeAndRejectsFutureClock() {
        FixFreshness.Tracker tracker = new FixFreshness.Tracker();
        FixFreshness.Sample fresh = tracker.observe(1000, 100000, 1010, 5000, 100010);
        assertTrue(fresh.isFresh("FINE", 5000, 100010, 1500));
        assertSame(fresh, tracker.observe(1000, 100000, 2010, 6000, 101010));
        assertFalse(fresh.isFresh("FINE", 6500, 101510, 1500));
        assertFalse(fresh.isFresh("COARSE", 5000, 100010, 1500));
        assertEquals(FixFreshness.UNKNOWN, tracker.observe(999, 100000, 1010, 5000, 100010).ageMs(5000, 100010));
        assertEquals(FixFreshness.UNKNOWN, tracker.observe(2000, 100000, 1990, 5000, 100010).ageMs(5000, 100010));
    }
}
