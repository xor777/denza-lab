package dev.denza.apps;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Live geometry of the native DiShare {@code ShareDialogActivity}, read from the
 * accessibility node tree. All rects are in real screen pixels. This replaces the
 * old hard-coded {@code LayoutProfile} constants: instead of guessing where the
 * native row/screens are, we read their actual bounds every time the window
 * changes, so the overlay stays aligned across freeform move/resize and layout
 * families.
 *
 * Node ids captured from the live dialog:
 * {@code central_screen} (source preview), {@code ar_hud_screen},
 * {@code fse_screen}, {@code app_list} + {@code app_icon} (only after App Change),
 * {@code switch_share_app} (App Change button), {@code close}.
 */
final class SimulcastDialogGeometry {
    private static final String PKG = "com.byd.dishare";

    final Rect dialog;
    final Rect central;
    final Rect hud;
    final Rect fse;
    final Rect appChangeButton;
    final Rect close;
    /** Bounds of the native row container (RecyclerView). Null unless App Change is open. */
    final Rect appList;
    /** Per-slot bounds of the native app row, left-to-right. Empty unless App Change is open. */
    final List<Rect> appSlots;

    private SimulcastDialogGeometry(Rect dialog, Rect central, Rect hud, Rect fse,
            Rect appChangeButton, Rect close, Rect appList, List<Rect> appSlots) {
        this.dialog = dialog;
        this.central = central;
        this.hud = hud;
        this.fse = fse;
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

    /**
     * Same on-screen layout as {@code other}? Used to ignore the window events our
     * own overlay windows generate (their add/remove doesn't move the dialog), so
     * we only re-apply when DiShare's geometry actually changes.
     */
    boolean sameAs(SimulcastDialogGeometry other) {
        // Deliberately excludes appSlots: those scroll within the container and would
        // otherwise make our row recompute (jump/resize) on every native scroll event.
        return other != null
                && equalRect(dialog, other.dialog)
                && equalRect(central, other.central)
                && equalRect(hud, other.hud)
                && equalRect(fse, other.fse)
                && equalRect(appChangeButton, other.appChangeButton)
                && equalRect(close, other.close)
                && equalRect(appList, other.appList);
    }

    private static boolean equalRect(Rect a, Rect b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Receiver id for a drop point, or null if it is not over a projectable screen.
     * {@code central} is the local/source screen and is intentionally not a target.
     */
    String receiverAt(float x, float y) {
        if (hud != null && hud.contains((int) x, (int) y)) {
            return "screen_hud";
        }
        if (fse != null && fse.contains((int) x, (int) y)) {
            return "screen_fse";
        }
        return null;
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
        return new SimulcastDialogGeometry(
                dialog,
                nodeBounds(root, "central_screen"),
                nodeBounds(root, "ar_hud_screen"),
                nodeBounds(root, "fse_screen"),
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
        return "Geometry{dialog=" + dialog + ", central=" + central + ", hud=" + hud
                + ", fse=" + fse + ", appChange=" + appChangeButton
                + ", slots=" + appSlots.size() + '}';
    }
}
