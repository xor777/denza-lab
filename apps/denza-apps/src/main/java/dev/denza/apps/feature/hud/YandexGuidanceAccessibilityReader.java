package dev.denza.apps.feature.hud;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

final class YandexGuidanceAccessibilityReader {
    private static final String PACKAGE = "ru.yandex.yandexnavi";
    private static final String ID_PREFIX = PACKAGE + ":id/";

    private YandexGuidanceAccessibilityReader() {
    }

    static HudGuidance read(AccessibilityService service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SparseArray<List<AccessibilityWindowInfo>> displays = service.getWindowsOnAllDisplays();
            if (displays != null) {
                for (int index = 0; index < displays.size(); index++) {
                    HudGuidance guidance = readWindows(displays.valueAt(index));
                    if (guidance != null) {
                        return guidance;
                    }
                }
            }
        }
        return readWindows(service.getWindows());
    }

    private static HudGuidance readWindows(List<AccessibilityWindowInfo> windows) {
        if (windows == null) {
            return null;
        }
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window == null ? null : window.getRoot();
            if (root == null) {
                continue;
            }
            try {
                CharSequence packageName = root.getPackageName();
                if (!PACKAGE.contentEquals(packageName)) {
                    continue;
                }
                HudGuidance guidance = readRoot(root);
                if (guidance != null) {
                    return guidance;
                }
            } finally {
                root.recycle();
            }
        }
        return null;
    }

    private static HudGuidance readRoot(AccessibilityNodeInfo root) {
        String instruction = firstNonEmpty(
                description(root, "image_maneuverballoon_maneuver"),
                description(root, "next_maneuver_image"),
                description(root, "next_upcoming_maneuver"));
        String nextRoadName = firstNonEmpty(
                text(root, "text_nextstreet"),
                text(root, "text_jointballoon_nextstreet"));
        String maneuverDistance = firstNonEmpty(
                text(root, "text_maneuverballoon_distance"),
                text(root, "next_maneuver_distance_value"),
                text(root, "next_upcoming_maneuver_distance"));
        String maneuverUnit = firstNonEmpty(
                text(root, "text_maneuverballoon_metrics"),
                text(root, "next_maneuver_distance_unit"));
        String remainingDistance = text(root, "textview_eta_distance");
        String remainingTime = text(root, "textview_eta_time");
        String eta = text(root, "textview_eta_arrival");
        return YandexGuidanceParser.parse(
                instruction,
                nextRoadName,
                maneuverDistance,
                maneuverUnit,
                remainingDistance,
                remainingTime,
                eta);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String text(AccessibilityNodeInfo root, String id) {
        return value(root, id, false);
    }

    private static String description(AccessibilityNodeInfo root, String id) {
        return value(root, id, true);
    }

    private static String value(AccessibilityNodeInfo root, String id, boolean description) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(ID_PREFIX + id);
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        String result = "";
        for (AccessibilityNodeInfo node : nodes) {
            try {
                CharSequence value = description ? node.getContentDescription() : node.getText();
                if (value != null && value.length() > 0 && result.isEmpty()) {
                    result = value.toString();
                }
            } finally {
                node.recycle();
            }
        }
        return result;
    }
}
