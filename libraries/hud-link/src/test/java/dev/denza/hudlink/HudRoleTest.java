package dev.denza.hudlink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.denza.hudlink.HudProtocol.Road;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** The placement table holds together: one owner per field, z as the doc gives them. */
public final class HudRoleTest {

    @Test
    public void everyRoadFieldBelongsToExactlyOneRoleAndHasAPlaceInThePacket() {
        Map<Integer, HudRole> owners = new HashMap<>();
        for (HudRole role : HudRole.values()) {
            for (int field : role.fields()) {
                HudRole earlier = owners.put(field, role);
                assertEquals("field " + field + " in two roles", null, earlier);
            }
        }
        assertEquals(HudRoadPacket.ORDER.length, owners.size());
        for (int field : HudRoadPacket.ORDER) {
            assertTrue("field " + field + " has no role", owners.containsKey(field));
        }
    }

    @Test
    public void layersGoToTheMapWindowAtTheDocumentsZ() {
        assertEquals(100, HudRole.MAP.z);
        assertEquals(200, HudRole.MAP_MARKS.z);
        assertEquals(300, HudRole.MAP_BADGE.z);
        for (HudRole role : HudRole.values()) {
            assertEquals(role.toString(), role.layered(), role.fields().length == 0);
            if (role.layered()) {
                assertEquals(HudWindow.MAP, role.window);
            }
        }
    }

    @Test
    public void theModuleOutranksTheCaptureWhereBothPublish() {
        for (HudRole role : HudRole.values()) {
            if (role.rank(HudSource.MODULE) >= 0 && role.rank(HudSource.CAPTURE) >= 0) {
                assertTrue(role.toString(), role.rank(HudSource.MODULE) < role.rank(HudSource.CAPTURE));
            }
        }
    }

    @Test
    public void theModulesRouteOpensNavigationByItselfAsBeforeTheCompositor() {
        HudFields summaryOnly = new HudFields.Builder().number(Road.REMAINING_METERS, 1_000).build();
        HudFields nothing = new HudFields.Builder().build();

        assertTrue(HudRole.opensNavigation(HudSource.MODULE, summaryOnly));
        assertTrue(HudRole.opensNavigation(HudSource.MODULE, nothing));
        assertEquals(false, HudRole.opensNavigation(HudSource.CAPTURE, summaryOnly));
        assertTrue(HudRole.opensNavigation(HudSource.CAPTURE,
                new HudFields.Builder().number(Road.MANEUVER_METERS, 0).build()));
    }

    @Test
    public void theManoeuvreBlockIsFilledByTheMetresToTheManoeuvre() {
        assertTrue(HudRole.NAVIGATION.carries(Road.MANEUVER_METERS));
        assertTrue(HudRole.fillsNavigation(new HudFields.Builder().number(Road.MANEUVER_METERS, 0).build()));
        assertEquals(false, HudRole.fillsNavigation(new HudFields.Builder()
                .bytes(Road.MANEUVER_PNG, new byte[] {1}).text(Road.ROAD_NAME, "Тверская").build()));
    }
}
