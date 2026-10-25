package dev.denza.adaslink.yandexnative;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeResourcesTest {
    @Test public void originalLocaleSpacingAndWordingRemainExact() {
        assertEquals("", NativeResources.get("route_browser_fragment_distance_space", false));
        assertEquals("\u00a0", NativeResources.get("route_browser_fragment_distance_space", true));
        assertEquals("area", NativeResources.get("navi_action_along_charge", true));
        assertEquals("Passing through the service", NativeResources.get("navi_action_along_rest", true));
        assertEquals("行驶", NativeResources.get("autonavi_car_result_share_driving", false));
        assertEquals("meter(s)", NativeResources.get("route_meter", true));
        assertEquals("3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793", NativeResources.APK_SHA256);
    }
    @Test public void everyOriginalRouteResourceHasBothConfigurations() {
        String[] names = {"autonavi_car_result_share_driving", "autonavi_car_result_share_enter", "km",
            "route_browser_fragment_distance_space", "route_foot_navi_no_name_road", "route_meter",
            "navi_action_turnleft", "navi_action_turnright", "navi_action_left_front", "navi_action_right_front",
            "navi_action_left_back", "navi_action_right_back", "navi_action_turn_left_back", "navi_action_enter_ring",
            "navi_action_leave_ring", "navi_action_along_rest", "navi_action_along_charge", "navi_action_along"};
        for (String name : names) {
            assertNotNull(NativeResources.get(name, false));
            assertNotNull(NativeResources.get(name, true));
        }
    }
    @Test(expected=IllegalArgumentException.class) public void unknownResourceIsNotInvented() {
        NativeResources.get("navi_action_not_in_original", true);
    }
    @Test(expected=IllegalArgumentException.class) public void missingNameIsNotInvented() {
        NativeResources.get(null, false);
    }
}
