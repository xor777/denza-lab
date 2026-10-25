package dev.denza.adaslink.yandexnative;

/** Original factory APK route-rendering resources, not road facts or adapter guesses.
 * Extracted from resources.arsc using aapt2 dump resources; default and en configs.
 * The English nonbreaking space and unusual original wording are intentional.
 */
public final class NativeResources {
    public static final String APK_SHA256 = "3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793";
    private NativeResources() {}

    /** Only the original DEFAULT and English resource configurations are represented. */
    public static String get(String originalResourceName, boolean english) {
        if (originalResourceName == null) throw new IllegalArgumentException("original resource name required");
        switch (originalResourceName) {
            case "autonavi_car_result_share_driving": return english ? "travel" : "行驶";
            case "autonavi_car_result_share_enter": return english ? "get into" : "进入";
            case "km": return english ? "km" : "公里";
            case "navi_action_along": return english ? "Straight ahead" : "直行";
            case "navi_action_along_charge": return english ? "area" : "经过收费站";
            case "navi_action_along_rest": return english ? "Passing through the service" : "经过服务区";
            case "navi_action_enter_ring": return english ? "Enter the roundabout" : "进入环岛";
            case "navi_action_leave_ring": return english ? "Drive out of the roundabout" : "驶出环岛";
            case "navi_action_left_back": return english ? "Turn left and back" : "向左后转";
            case "navi_action_left_front": return english ? "Drive forward to the left" : "向左前方行驶";
            case "navi_action_right_back": return english ? "Turn right back" : "向右后转";
            case "navi_action_right_front": return english ? "Drive right ahead" : "向右前方行驶";
            case "navi_action_turn_left_back": return english ? "Turn left and turn around" : "左转调头";
            case "navi_action_turnleft": return english ? "Turn left" : "左转";
            case "navi_action_turnright": return english ? "Turn right" : "右转";
            case "route_browser_fragment_distance_space": return english ? "\u00a0" : "";
            case "route_foot_navi_no_name_road": return english ? "Nameless Road" : "无名道路";
            case "route_meter": return english ? "meter(s)" : "米";
            default: throw new IllegalArgumentException("unrepresented original resource: " + originalResourceName);
        }
    }
}
