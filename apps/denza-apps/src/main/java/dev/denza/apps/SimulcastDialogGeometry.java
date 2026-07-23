package dev.denza.apps;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import dev.denza.apps.feature.simulcast.ScreenTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live geometry of the native DiShare {@code ShareDialogActivity}, read from the
 * accessibility node tree. All rects are in real screen pixels. This replaces the
 * old hard-coded {@code LayoutProfile} constants: instead of guessing where the
 * native row/screens are, we read their actual bounds every time the window
 * changes, so the overlay stays aligned across freeform move/resize and layout
 * families.
 *
 * Node ids captured from the live dialog:
 * {@code central_screen} (source preview), all receiver cards described by
 * {@link ScreenTarget}, {@code app_list} + {@code app_icon} (only after App Change),
 * {@code switch_share_app} (App Change button), {@code close}.
 */
final class SimulcastDialogGeometry {
    private static final String PKG = "com.byd.dishare";

    final Rect dialog;
    final Rect central;
    /** Exact inner content bounds inside the native blue selected frame. */
    final Rect centralContent;
    final Map<String, Rect> receivers;
    final Rect appChangeButton;
    final Rect close;
    /** Bounds of the native row container (RecyclerView). Null unless App Change is open. */
    final Rect appList;
    /** Per-slot bounds of the native app row, left-to-right. Empty unless App Change is open. */
    final List<Rect> appSlots;

    private SimulcastDialogGeometry(Rect dialog, Rect central, Rect centralContent,
            Map<String, Rect> receivers, Rect appChangeButton, Rect close, Rect appList,
            List<Rect> appSlots) {
        this.dialog = dialog;
        this.central = central;
        this.centralContent = centralContent;
        this.receivers = receivers;
        this.appChangeButton = appChangeButton;
        this.close = close;
        this.appList = appList;
        this.appSlots = appSlots;
    }

    /**
     * True when there is a stable on-screen area for the custom app picker. If native
     * App Change is already open this is the real row container; otherwise it is a
     * synthetic row area derived from the visible App Change button.
     */
    boolean isAppPickerOpen() {
        return appList != null;
    }

    /** Same exact layout, excluding native row slots that can scroll independently. */
    boolean sameLayoutAs(SimulcastDialogGeometry other) {
        return equivalentWithin(other, 0);
    }

    /**
     * Equivalent on-screen layout within {@code epsilonPx}. Native row slots and the
     * App Change button are deliberately excluded because they can move independently
     * inside the stable row container.
     */
    boolean equivalentWithin(SimulcastDialogGeometry other, int epsilonPx) {
        // Deliberately excludes appSlots: those scroll within the container and would
        // otherwise make our row recompute (jump/resize) on every native scroll event.
        return other != null
                && approximatelyEqualRect(dialog, other.dialog, epsilonPx)
                && approximatelyEqualRect(central, other.central, epsilonPx)
                && approximatelyEqualRect(centralContent, other.centralContent, epsilonPx)
                && approximatelyEqualMaps(receivers, other.receivers, epsilonPx)
                && approximatelyEqualRect(close, other.close, epsilonPx)
                && approximatelyEqualRect(appList, other.appList, epsilonPx);
    }

    private static boolean approximatelyEqualMaps(
            Map<String, Rect> first,
            Map<String, Rect> second,
            int tolerancePx) {
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        for (String key : first.keySet()) {
            if (!approximatelyEqualRect(first.get(key), second.get(key), tolerancePx)) {
                return false;
            }
        }
        return true;
    }

    private static boolean approximatelyEqualRect(Rect a, Rect b, int tolerancePx) {
        if (a == null || b == null) {
            return a == b;
        }
        return Math.abs(a.left - b.left) <= tolerancePx
                && Math.abs(a.top - b.top) <= tolerancePx
                && Math.abs(a.right - b.right) <= tolerancePx
                && Math.abs(a.bottom - b.bottom) <= tolerancePx;
    }

    /**
     * Receiver id for a drop point, or null if it is not over a projectable screen.
     * {@code central} is the local/source screen and is intentionally not a target.
     */
    String receiverAt(float x, float y, Set<String> availableReceivers) {
        if (availableReceivers == null || availableReceivers.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Rect> entry : receivers.entrySet()) {
            if (availableReceivers.contains(entry.getKey())
                    && entry.getValue().contains((int) x, (int) y)) {
                return entry.getKey();
            }
        }
        return null;
    }

    Map<String, Rect> availableReceiverBounds(Set<String> availableReceivers) {
        if (availableReceivers == null || availableReceivers.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, Rect> result = new LinkedHashMap<>();
        for (Map.Entry<String, Rect> entry : receivers.entrySet()) {
            if (availableReceivers.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    Set<String> visibleViewResourceNames() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ScreenTarget target : ScreenTarget.SUPPORTED) {
            if (receivers.containsKey(target.receiverId)) {
                result.add(target.viewResourceName);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static SimulcastDialogGeometry from(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        Rect dialog = nodeBounds(root, "share_dialog");
        if (dialog == null) {
            dialog = nodeBounds(root, "mutil_screen_view");
        }
        if (dialog == null) {
            return null;
        }
        List<Rect> slots = allNodeBounds(root, "app_icon");
        Rect appChangeButton = nodeBounds(root, "switch_share_app");
        Rect appList = nodeBounds(root, "app_list");
        if (appList == null && appChangeButton != null) {
            appList = appListFromButton(appChangeButton);
        }
        LinkedHashMap<String, Rect> receivers = new LinkedHashMap<>();
        for (ScreenTarget target : ScreenTarget.SUPPORTED) {
            Rect bounds = nodeBounds(root, target.viewResourceName);
            if (bounds != null) {
                receivers.put(target.receiverId, bounds);
            }
        }
        Rect central = nodeBounds(root, "central_screen");
        Rect centralContent = descendantNodeBounds(
                root,
                "central_screen",
                "screen_card_view");
        return new SimulcastDialogGeometry(
                dialog,
                central,
                centralContent,
                Collections.unmodifiableMap(receivers),
                appChangeButton,
                nodeBounds(root, "close"),
                appList,
                slots);
    }

    private static Rect appListFromButton(Rect button) {
        int width = Math.round(button.width() * 2.0f);
        int height = Math.round(button.height() * 1.5f);
        int centerX = button.centerX();
        int centerY = button.centerY() - Math.round(button.height() * 0.4f);
        return new Rect(centerX - width / 2, centerY - height / 2,
                centerX + width / 2, centerY + height / 2);
    }

    private static Rect nodeBounds(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(PKG + ":id/" + id);
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        Rect r = new Rect();
        nodes.get(0).getBoundsInScreen(r);
        for (AccessibilityNodeInfo n : nodes) {
            n.recycle();
        }
        return r.isEmpty() ? null : r;
    }

    private static Rect descendantNodeBounds(
            AccessibilityNodeInfo root,
            String parentId,
            String childId) {
        Rect parentBounds = nodeBounds(root, parentId);
        if (parentBounds == null) {
            return null;
        }
        Rect result = null;
        List<AccessibilityNodeInfo> children =
                root.findAccessibilityNodeInfosByViewId(PKG + ":id/" + childId);
        if (children != null) {
            for (AccessibilityNodeInfo child : children) {
                Rect bounds = new Rect();
                child.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()
                        && parentBounds.contains(bounds.centerX(), bounds.centerY())) {
                    result = bounds;
                    break;
                }
            }
            for (AccessibilityNodeInfo child : children) {
                child.recycle();
            }
        }
        return result;
    }

    private static List<Rect> allNodeBounds(AccessibilityNodeInfo root, String id) {
        List<Rect> out = new ArrayList<>();
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(PKG + ":id/" + id);
        if (nodes != null) {
            for (AccessibilityNodeInfo n : nodes) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    out.add(r);
                }
                n.recycle();
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Geometry{dialog=" + dialog + ", central=" + central
                + ", centralContent=" + centralContent
                + ", receivers=" + receivers + ", appChange=" + appChangeButton
                + ", slots=" + appSlots.size() + '}';
    }
}
